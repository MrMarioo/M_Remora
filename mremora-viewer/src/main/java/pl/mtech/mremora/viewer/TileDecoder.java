package pl.mtech.mremora.viewer;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.MemoryCacheImageInputStream;
import pl.mtech.mremora.protocol.RectPixels;
import pl.mtech.mremora.protocol.ScreenInfo;
import pl.mtech.mremora.protocol.TileUpdate;

/**
 * Decodes {@link TileUpdate} parts on its own {@code mremora-decoder} thread, joins the parts of one frame and hands
 * complete {@link DecodedTiles} to the UI in order.
 *
 * <p>Tile updates are deltas, so unlike {@link FrameDecoder} nothing is ever skipped: the queue is unbounded
 * (the agent's ACK gate bounds it). A rectangle that cannot be decoded would leave a permanent hole in the image,
 * so it ends the stream: {@code onCorrupt("protocol error")} is called once and nothing is delivered afterwards.
 *
 * <p>Memory taken by agent-supplied sizes is bounded: the agent never upscales, so a stream larger than its
 * {@link ScreenInfo} is corrupt, and so is a frame whose rectangles add up to more than
 * {@link #MAX_FRAME_AREA_FACTOR} times the stream area.
 */
final class TileDecoder implements AutoCloseable {

    private static final Logger LOG = System.getLogger(TileDecoder.class.getName());
    private static final String THREAD_NAME = "mremora-decoder";
    /** Largest rectangle accepted (pixels); anything bigger is rejected before any allocation. */
    static final long MAX_RECT_PIXELS = 16L * 1024 * 1024;
    static final String CORRUPT_REASON = "protocol error";
    /** A frame may cover its stream at most this many times over (all parts together). */
    static final long MAX_FRAME_AREA_FACTOR = 2;

    private final Supplier<ScreenInfo> screen;
    private final Executor deliverOn;
    private final Consumer<DecodedTiles> sink;
    private final Consumer<String> onCorrupt;
    private final ExecutorService decoderThread;

    private volatile boolean closed;
    private volatile boolean failed;
    private final java.util.concurrent.atomic.AtomicLong partsProcessed = new java.util.concurrent.atomic.AtomicLong();

    // decoder-thread state: the frame being assembled
    private final List<DecodedTiles.Patch> patches = new ArrayList<>();
    private long currentSequence;
    private int currentWidth;
    private int currentHeight;
    private int currentBytes;
    private long currentPixels;
    private boolean assembling;

    /**
     * @param screen the agent's screen, bounding the stream size; called on the decoder thread before each part,
     *     and may block until the handshake that tells it is done (parts only arrive after it)
     */
    TileDecoder(Supplier<ScreenInfo> screen, Executor deliverOn, Consumer<DecodedTiles> sink,
                Consumer<String> onCorrupt) {
        this.screen = Objects.requireNonNull(screen, "screen");
        this.deliverOn = Objects.requireNonNull(deliverOn, "deliverOn");
        this.sink = Objects.requireNonNull(sink, "sink");
        this.onCorrupt = Objects.requireNonNull(onCorrupt, "onCorrupt");
        this.decoderThread = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, THREAD_NAME);
            thread.setDaemon(true);
            return thread;
        });
    }

    /** Queues a part for decoding. Never blocks; ignored after {@link #close()} or a corrupt part. */
    void offer(TileUpdate part) {
        Objects.requireNonNull(part, "part");
        if (closed || failed) {
            return;
        }
        try {
            decoderThread.execute(() -> process(part));
        } catch (RejectedExecutionException e) {
            // closed concurrently
        }
    }

    /** Stops the decoder thread; nothing reaches the sink or the corrupt callback after this. Idempotent. */
    @Override
    public void close() {
        closed = true;
        decoderThread.shutdownNow();
    }

    /** Parts taken off the queue so far (decoded, rejected or ignored); for tests. */
    long partsProcessed() {
        return partsProcessed.get();
    }

    /** Patches of the frame being assembled; for tests, read only after the decoder signalled (sink, corrupt). */
    int heldPatches() {
        return patches.size();
    }

    private void process(TileUpdate part) {
        try {
            processPart(part);
        } finally {
            partsProcessed.incrementAndGet();
        }
    }

    private void processPart(TileUpdate part) {
        if (closed || failed) {
            return;
        }
        try {
            if (assembling && (part.sequence() != currentSequence
                    || part.streamWidth() != currentWidth || part.streamHeight() != currentHeight)) {
                throw new IllegalArgumentException("Part of frame " + part.sequence() + " inside frame " + currentSequence);
            }
            ScreenInfo limit = Objects.requireNonNull(screen.get(), "screen");
            if (part.streamWidth() > limit.width() || part.streamHeight() > limit.height()) {
                throw new IllegalArgumentException("Stream %dx%d is larger than the %dx%d screen".formatted(
                        part.streamWidth(), part.streamHeight(), limit.width(), limit.height()));
            }
            if (!assembling) {
                assembling = true;
                currentSequence = part.sequence();
                currentWidth = part.streamWidth();
                currentHeight = part.streamHeight();
                currentBytes = 0;
                currentPixels = 0;
                patches.clear();
            }
            long maxPixels = MAX_FRAME_AREA_FACTOR * currentWidth * currentHeight;
            for (TileUpdate.Rect rect : part.rects()) {
                currentPixels += (long) rect.width() * rect.height();
                if (currentPixels > maxPixels) {
                    throw new IllegalArgumentException("Frame " + currentSequence + " covers more than "
                            + MAX_FRAME_AREA_FACTOR + "x its " + currentWidth + "x" + currentHeight + " stream");
                }
                patches.add(decode(rect));
                currentBytes += rect.dataLength();
            }
        } catch (RuntimeException | IOException | OutOfMemoryError e) {
            failed = true;
            // Release the decoded patches of the broken frame before anything else (this may be an OOM).
            patches.clear();
            assembling = false;
            LOG.log(Level.WARNING, "Corrupt tile update " + part.sequence() + ", ending the stream", e);
            try {
                deliverOn.execute(() -> {
                    if (!closed) {
                        onCorrupt.accept(CORRUPT_REASON);
                    }
                });
            } catch (RuntimeException ex) {
                LOG.log(Level.WARNING, "Cannot report a corrupt stream to the UI", ex);
            }
            return;
        }
        if (part.lastPart()) {
            DecodedTiles frame = new DecodedTiles(currentSequence, currentWidth, currentHeight,
                    List.copyOf(patches), currentBytes);
            assembling = false;
            patches.clear();
            deliver(frame);
        }
    }

    private void deliver(DecodedTiles frame) {
        try {
            deliverOn.execute(() -> {
                if (closed) {
                    return;
                }
                try {
                    sink.accept(frame);
                } catch (RuntimeException | Error e) {
                    LOG.log(Level.WARNING, "Displaying frame " + frame.sequence() + " failed", e);
                }
            });
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Cannot hand a decoded frame over to the UI", e);
        }
    }

    private static DecodedTiles.Patch decode(TileUpdate.Rect rect) throws IOException {
        int w = rect.width();
        int h = rect.height();
        long area = (long) w * h;
        if (area > MAX_RECT_PIXELS) {
            throw new IllegalArgumentException("Rectangle too large: " + rect);
        }
        int[] argb;
        switch (rect.encoding()) {
            case SOLID -> {
                argb = new int[Math.toIntExact(area)];
                Arrays.fill(argb, RectPixels.decodeSolid(rect.data()));
            }
            case LOSSLESS -> argb = RectPixels.decodeLossless(rect.data(), w, h);
            case JPEG -> argb = PixelConversion.toArgb(readJpeg(rect.data(), w, h));
            default -> throw new IllegalArgumentException("Unsupported encoding " + rect.encoding());
        }
        return new DecodedTiles.Patch(rect.x(), rect.y(), w, h, argb);
    }

    /** Reads the image only after its header says it is exactly {@code w x h}, so a lying size allocates nothing. */
    private static BufferedImage readJpeg(byte[] data, int w, int h) throws IOException {
        try (ImageInputStream input = new MemoryCacheImageInputStream(new ByteArrayInputStream(data))) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                throw new IllegalArgumentException("JPEG rectangle is not a readable image");
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                if (reader.getWidth(0) != w || reader.getHeight(0) != h) {
                    throw new IllegalArgumentException("JPEG rectangle is %dx%d, not %dx%d".formatted(
                            reader.getWidth(0), reader.getHeight(0), w, h));
                }
                BufferedImage image = reader.read(0);
                if (image == null) {
                    throw new IllegalArgumentException("JPEG rectangle is not a readable " + w + "x" + h + " image");
                }
                return image;
            } finally {
                reader.dispose();
            }
        }
    }
}

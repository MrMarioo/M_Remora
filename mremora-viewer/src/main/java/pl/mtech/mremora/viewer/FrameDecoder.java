package pl.mtech.mremora.viewer;

import java.awt.image.BufferedImage;
import java.awt.image.ComponentSampleModel;
import java.awt.image.DataBufferByte;
import java.awt.image.Raster;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;
import pl.mtech.mremora.protocol.FrameUpdate;

/**
 * Decodes JPEG frames to ARGB pixels on its own {@code mremora-decoder} thread and hands them to the UI.
 *
 * <p>Only the newest frame matters, so there are no queues: one slot holds the newest frame waiting to be decoded
 * and one holds the newest decoded frame waiting for the UI. A newer frame replaces an older one in either slot,
 * so a slow decoder or a slow UI skips frames instead of falling behind.
 */
final class FrameDecoder implements AutoCloseable {

    private static final Logger LOG = System.getLogger(FrameDecoder.class.getName());
    private static final String THREAD_NAME = "mremora-decoder";

    private final Executor deliverOn;
    private final Consumer<DecodedFrame> sink;
    private final ExecutorService decoderThread;

    private final AtomicReference<FrameUpdate> incoming = new AtomicReference<>();
    private final AtomicReference<DecodedFrame> ready = new AtomicReference<>();
    private final AtomicBoolean decodeScheduled = new AtomicBoolean();
    private final AtomicBoolean deliveryScheduled = new AtomicBoolean();
    private final AtomicLong framesProcessed = new AtomicLong();
    private volatile boolean closed;

    /**
     * @param deliverOn runs the hand-over to {@code sink}, normally {@code Platform::runLater}
     * @param sink receives decoded frames on {@code deliverOn}
     */
    FrameDecoder(Executor deliverOn, Consumer<DecodedFrame> sink) {
        this.deliverOn = Objects.requireNonNull(deliverOn, "deliverOn");
        this.sink = Objects.requireNonNull(sink, "sink");
        this.decoderThread = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, THREAD_NAME);
            thread.setDaemon(true);
            return thread;
        });
    }

    /** Queues a frame for decoding, replacing one not yet decoded. Never blocks; ignored after {@link #close()}. */
    void offer(FrameUpdate frame) {
        Objects.requireNonNull(frame, "frame");
        if (closed) {
            return;
        }
        incoming.set(frame);
        scheduleDecode();
    }

    /** Stops the decoder thread; frames not yet delivered are dropped, none reaches the sink after this. Idempotent. */
    @Override
    public void close() {
        closed = true;
        decoderThread.shutdownNow();
    }

    /** Frames taken by the decoder so far, decoded or skipped as corrupt; for tests. */
    long framesProcessed() {
        return framesProcessed.get();
    }

    private void scheduleDecode() {
        if (decodeScheduled.compareAndSet(false, true)) {
            try {
                decoderThread.execute(this::decodeLoop);
            } catch (RejectedExecutionException e) {
                // closed concurrently; the frame is dropped
            }
        }
    }

    private void decodeLoop() {
        decodeScheduled.set(false);
        FrameUpdate frame = incoming.getAndSet(null);
        if (frame == null) {
            return;
        }
        try {
            DecodedFrame decoded = decode(frame);
            if (decoded != null) {
                ready.set(decoded);
                scheduleDelivery();
            }
        } finally {
            framesProcessed.incrementAndGet();
        }
        // No re-check of `incoming` here: decodeScheduled was cleared on entry, so any offer made since then
        // has already scheduled the next run itself.
    }

    private void scheduleDelivery() {
        if (deliveryScheduled.compareAndSet(false, true)) {
            try {
                deliverOn.execute(this::deliver);
            } catch (RuntimeException e) {
                deliveryScheduled.set(false);
                LOG.log(Level.WARNING, "Cannot hand a decoded frame over to the UI", e);
            }
        }
    }

    private void deliver() {
        deliveryScheduled.set(false);
        if (closed) {
            return;
        }
        DecodedFrame decoded = ready.getAndSet(null);
        if (decoded == null) {
            return;
        }
        try {
            sink.accept(decoded);
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Displaying frame " + decoded.sequence() + " failed", e);
        }
    }

    private static DecodedFrame decode(FrameUpdate frame) {
        try {
            // Memory cache: the default InputStream path would create a temp file for every frame.
            var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(frame.data()));
            BufferedImage image = ImageIO.read(input);
            if (image == null) {
                LOG.log(Level.WARNING, "Frame {0} is not a readable image, skipped", frame.sequence());
                return null;
            }
            int width = image.getWidth();
            int height = image.getHeight();
            int[] argb = toArgb(image);
            return new DecodedFrame(frame.sequence(), width, height, argb, frame.dataLength());
        } catch (IOException | RuntimeException e) {
            LOG.log(Level.WARNING, "Frame " + frame.sequence() + " could not be decoded, skipped", e);
            return null;
        }
    }

    /**
     * Converts to packed ARGB. JPEGs decode to {@code TYPE_3BYTE_BGR}, which is read straight from its byte buffer;
     * {@link BufferedImage#getRGB} goes through the color model per pixel (~43 ms per 1080p frame) and stays the
     * fallback for every other layout.
     */
    private static int[] toArgb(BufferedImage image) {
        int width = image.getWidth();
        int height = image.getHeight();
        if (image.getType() == BufferedImage.TYPE_3BYTE_BGR) {
            int[] argb = fromBgrBytes(image.getRaster(), width, height);
            if (argb != null) {
                return argb;
            }
        }
        return image.getRGB(0, 0, width, height, null, 0, width);
    }

    /** Returns {@code null} when the raster is not the plain interleaved B,G,R byte layout. */
    private static int[] fromBgrBytes(Raster raster, int width, int height) {
        if (!(raster.getSampleModel() instanceof ComponentSampleModel model)
                || !(raster.getDataBuffer() instanceof DataBufferByte buffer)
                || buffer.getNumBanks() != 1
                || model.getNumBands() != 3
                || raster.getSampleModelTranslateX() != 0
                || raster.getSampleModelTranslateY() != 0) {
            return null;
        }
        int[] bandOffsets = model.getBandOffsets(); // index 0 = red, 1 = green, 2 = blue
        int pixelStride = model.getPixelStride();
        int scanlineStride = model.getScanlineStride();
        byte[] data = buffer.getData();
        int base = buffer.getOffset();
        int rOff = bandOffsets[0];
        int gOff = bandOffsets[1];
        int bOff = bandOffsets[2];
        int[] argb = new int[width * height];
        int i = 0;
        for (int y = 0; y < height; y++) {
            int p = base + y * scanlineStride;
            for (int x = 0; x < width; x++, p += pixelStride) {
                argb[i++] = 0xFF000000
                        | (data[p + rOff] & 0xFF) << 16
                        | (data[p + gOff] & 0xFF) << 8
                        | (data[p + bOff] & 0xFF);
            }
        }
        return argb;
    }
}

package pl.mtech.mremora.viewer;

import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;
import pl.mtech.mremora.protocol.ScreenInfo;
import pl.mtech.mremora.protocol.TileUpdate;
import pl.mtech.mremora.protocol.udp.CompleteRect;
import pl.mtech.mremora.protocol.udp.TileFragment;
import pl.mtech.mremora.protocol.udp.TileGrid;
import pl.mtech.mremora.protocol.udp.TileVersions;

/**
 * Decodes the rectangles of the UDP picture (protocol v3) on its own {@code mremora-decoder} thread and hands them to
 * the UI in batches. Unlike {@link TileDecoder} there are no frames: rectangles arrive in any order and each ends on
 * its own, reported through {@link Outcomes} (the viewer's ACKs tell the agent).
 *
 * <p>Per rectangle, in this order: the stream must not be larger than the agent's {@link ScreenInfo} (the agent never
 * upscales; otherwise the stream is corrupt, see below); the limits of {@link TileDecoder}
 * ({@link TileDecoder#MAX_RECT_PIXELS}, a JPEG checked by its header before decoding, rectangle inside the stream);
 * the epoch (a newer one starts new {@link TileVersions} for its stream size; an older one, or the current one with
 * another size, is dropped); decoding; then {@link TileVersions#claimNewer}: only the tiles not already painted by a
 * newer capture are kept, as sub-rectangles of the decoded pixels (one per run of tiles in a tile row). A rectangle
 * with nothing newer is reported {@link Outcomes#applied applied} at once, without the UI. A rectangle that fails
 * before or after the claim is {@link Outcomes#dropped dropped} with a {@code WARNING}; the stream goes on (the agent
 * resends its tiles).
 *
 * <p>Batches: kept rectangles are collected and sent to the UI as one {@link DecodedTiles} when the decoder's queue is
 * empty or {@value #MAX_BATCH_RECTS} rectangles are collected; a batch is also sent before an epoch change (it has one
 * stream size) and before a rectangle that would make it cover more than {@link TileDecoder#MAX_FRAME_AREA_FACTOR}
 * times its stream area (that rectangle starts the next batch). Its {@code sequence} is the largest {@code captureSeq}
 * in it. On the UI the sink returns whether it took the batch: then every rectangle of it is reported applied;
 * {@code false} (no view yet) reports them dropped, so the agent sends their tiles again.
 *
 * <p>UI backlog: at most {@value #MAX_POSTED_BATCHES} batches are posted to the UI and not yet run. Beyond that the
 * decoder thread waits (interruptibly; {@link #close()} releases it), so a stalled UI thread keeps the backlog as the
 * still encoded rectangles in the decoder's queue, not as decoded pixels. The agent's capture gate (at most two
 * unresolved captures) bounds that queue in turn.
 *
 * <p>Corrupt stream (like {@link TileDecoder}): a stream larger than the agent's screen, or a sink that throws, calls
 * {@code onCorrupt("protocol error")} once on the UI executor; nothing is decoded or delivered afterwards.
 */
final class UdpTileDecoder implements AutoCloseable {

    /** How rectangles ended; called on the decoder thread (skipped, failed) or the UI thread (batch applied). */
    interface Outcomes {

        /** The rectangle was painted, or skipped because all its tiles already carry a newer version. */
        void applied(long rectId);

        /** The rectangle was discarded (old epoch, failed checks or decoding, batch not taken by the UI). */
        void dropped(long rectId);
    }

    private static final Logger LOG = System.getLogger(UdpTileDecoder.class.getName());
    private static final String THREAD_NAME = "mremora-decoder";
    /** A batch is handed to the UI at the latest when it holds this many rectangles. */
    static final int MAX_BATCH_RECTS = 16;
    /** Batches posted to the UI and not yet run, at most. */
    static final int MAX_POSTED_BATCHES = 2;
    static final String CORRUPT_REASON = TileDecoder.CORRUPT_REASON;

    private final Supplier<ScreenInfo> screen;
    private final Executor deliverOn;
    private final Predicate<DecodedTiles> sink;
    private final Outcomes outcomes;
    private final Consumer<String> onCorrupt;
    private final ExecutorService decoderThread;
    /** Rectangles offered and not yet taken by the decoder thread. */
    private final AtomicInteger queued = new AtomicInteger();

    private volatile boolean closed;
    /** Set once by the first corruption (decoder or UI thread). */
    private final AtomicBoolean failed = new AtomicBoolean();
    /** Guards {@link #posted} and {@link #waitingForUi}; notified when a batch ran and on close. */
    private final Object postedLock = new Object();
    private int posted;
    private boolean waitingForUi;

    // Decoder-thread state.
    private int epoch = -1;
    private int streamWidth;
    private int streamHeight;
    private TileGrid grid;
    private TileVersions versions;
    private final List<DecodedTiles.Patch> batchPatches = new ArrayList<>();
    private final List<Long> batchRects = new ArrayList<>();
    private long batchSequence;
    private int batchBytes;
    private long batchPixels;

    /**
     * @param screen the agent's screen, bounding the stream size; called on the decoder thread before each rectangle,
     *     and may block until the handshake that tells it is done
     * @param sink   applies a batch on the UI; returns {@code false} when it cannot take it yet (no view)
     */
    UdpTileDecoder(Supplier<ScreenInfo> screen, Executor deliverOn, Predicate<DecodedTiles> sink, Outcomes outcomes,
                   Consumer<String> onCorrupt) {
        this.screen = Objects.requireNonNull(screen, "screen");
        this.deliverOn = Objects.requireNonNull(deliverOn, "deliverOn");
        this.sink = Objects.requireNonNull(sink, "sink");
        this.outcomes = Objects.requireNonNull(outcomes, "outcomes");
        this.onCorrupt = Objects.requireNonNull(onCorrupt, "onCorrupt");
        this.decoderThread = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, THREAD_NAME);
            thread.setDaemon(true);
            return thread;
        });
    }

    /** Queues a rectangle for decoding. Never blocks; ignored after {@link #close()} or a corrupt stream. */
    void offer(CompleteRect rect) {
        Objects.requireNonNull(rect, "rect");
        if (closed || failed.get()) {
            return;
        }
        queued.incrementAndGet();
        try {
            decoderThread.execute(() -> process(rect));
        } catch (RejectedExecutionException e) {
            queued.decrementAndGet(); // closed concurrently
        }
    }

    /** Stops the decoder thread; nothing reaches the sink, {@link Outcomes} or the corrupt callback after this. */
    @Override
    public void close() {
        closed = true;
        synchronized (postedLock) {
            postedLock.notifyAll(); // a decoder thread waiting for the UI gives up
        }
        decoderThread.shutdownNow();
    }

    /** Tests: batches posted to the UI and not yet run. */
    int postedBatches() {
        synchronized (postedLock) {
            return posted;
        }
    }

    /** Tests: whether the decoder thread waits for the UI to run a posted batch. */
    boolean waitingForUi() {
        synchronized (postedLock) {
            return waitingForUi;
        }
    }

    /** Tests: whether the decoder thread has stopped (after {@link #close()}). */
    boolean stopped() {
        return decoderThread.isTerminated();
    }

    private void process(CompleteRect rect) {
        queued.decrementAndGet();
        if (closed || failed.get()) {
            return;
        }
        ScreenInfo limit;
        try {
            limit = Objects.requireNonNull(screen.get(), "screen");
        } catch (RuntimeException e) {
            return; // closed while waiting for the handshake (or it failed): the session is ending
        }
        if (rect.streamWidth() > limit.width() || rect.streamHeight() > limit.height()) {
            corrupt("Stream %dx%d is larger than the %dx%d screen, ending the stream".formatted(
                    rect.streamWidth(), rect.streamHeight(), limit.width(), limit.height()), null);
            return;
        }
        try {
            decodeAndClaim(rect);
        } catch (RuntimeException | IOException | OutOfMemoryError e) {
            if (closed) {
                return;
            }
            LOG.log(Level.WARNING, "Dropping UDP rectangle " + rect, e);
            outcomes.dropped(rect.rectId());
        }
        if (closed) {
            return;
        }
        if (!batchRects.isEmpty() && (queued.get() == 0 || batchRects.size() >= MAX_BATCH_RECTS)) {
            flush();
        }
    }

    private void decodeAndClaim(CompleteRect rect) throws IOException {
        int sw = rect.streamWidth();
        int sh = rect.streamHeight();
        if (sw < 1 || sh < 1) {
            throw new IllegalArgumentException("Empty stream %dx%d".formatted(sw, sh));
        }
        int w = rect.width();
        int h = rect.height();
        if (w < 1 || h < 1 || rect.x() < 0 || rect.y() < 0
                || rect.x() + (long) w > sw || rect.y() + (long) h > sh) {
            throw new IllegalArgumentException("Rectangle outside the %dx%d stream".formatted(sw, sh));
        }
        if ((long) w * h > TileDecoder.MAX_RECT_PIXELS) {
            throw new IllegalArgumentException("Rectangle too large");
        }
        TileUpdate.Encoding encoding = switch (rect.encoding()) {
            case TileFragment.SOLID -> TileUpdate.Encoding.SOLID;
            case TileFragment.LOSSLESS -> TileUpdate.Encoding.LOSSLESS;
            case TileFragment.JPEG -> TileUpdate.Encoding.JPEG;
            default -> throw new IllegalArgumentException("Unsupported encoding " + rect.encoding());
        };
        if (rect.epoch() < epoch) {
            throw new IllegalArgumentException("Rectangle of the old epoch " + rect.epoch() + " in " + epoch);
        }
        if (rect.epoch() == epoch && (sw != streamWidth || sh != streamHeight)) {
            throw new IllegalArgumentException("Stream %dx%d inside the %dx%d epoch %d".formatted(
                    sw, sh, streamWidth, streamHeight, epoch));
        }
        int[] argb = TileDecoder.pixels(encoding, rect.data(), w, h);
        if (rect.epoch() > epoch) {
            if (!batchRects.isEmpty()) {
                flush(); // a batch has one stream size
            }
            epoch = rect.epoch();
            streamWidth = sw;
            streamHeight = sh;
            grid = new TileGrid(sw, sh);
            versions = new TileVersions(grid);
        }
        BitSet newer = versions.claimNewer(rect.x(), rect.y(), w, h, rect.captureSeq());
        if (newer.isEmpty()) {
            if (!closed) {
                outcomes.applied(rect.rectId());
            }
            return;
        }
        List<DecodedTiles.Patch> patches;
        try {
            patches = patchesOf(rect, argb, newer);
        } catch (RuntimeException | OutOfMemoryError e) {
            // Claimed but not batched: report it dropped, so the agent forces these tiles again.
            LOG.log(Level.WARNING, "Dropping UDP rectangle " + rect, e);
            if (!closed) {
                outcomes.dropped(rect.rectId());
            }
            return;
        }
        long pixels = 0;
        for (DecodedTiles.Patch p : patches) {
            pixels += (long) p.width() * p.height();
        }
        if (!batchRects.isEmpty()
                && batchPixels + pixels > TileDecoder.MAX_FRAME_AREA_FACTOR * streamWidth * streamHeight) {
            flush(); // the batch would cover its stream too many times over: this rect starts the next one
        }
        batchPatches.addAll(patches);
        batchPixels += pixels;
        batchRects.add(rect.rectId());
        batchSequence = batchRects.size() == 1 ? rect.captureSeq() : Math.max(batchSequence, rect.captureSeq());
        batchBytes += rect.data().length;
    }

    /** The parts of the rectangle covered by {@code newer} tiles: one patch per run of tiles in a tile row. */
    private List<DecodedTiles.Patch> patchesOf(CompleteRect rect, int[] argb, BitSet newer) {
        BitSet all = grid.tilesOf(rect.x(), rect.y(), rect.width(), rect.height());
        if (newer.equals(all)) {
            return List.of(new DecodedTiles.Patch(rect.x(), rect.y(), rect.width(), rect.height(), argb));
        }
        List<DecodedTiles.Patch> patches = new ArrayList<>();
        int x0 = rect.x();
        int y0 = rect.y();
        int x1 = x0 + rect.width();
        int y1 = y0 + rect.height();
        int cols = grid.cols();
        for (int t = newer.nextSetBit(0); t >= 0; ) {
            int row = t / cols;
            int end = newer.nextClearBit(t);
            int runEnd = Math.min(end, (row + 1) * cols); // a run never wraps to the next tile row
            int px0 = Math.max(x0, (t % cols) * TileGrid.TILE);
            int px1 = Math.min(x1, ((runEnd - 1) % cols + 1) * TileGrid.TILE);
            int py0 = Math.max(y0, row * TileGrid.TILE);
            int py1 = Math.min(y1, (row + 1) * TileGrid.TILE);
            patches.add(subPatch(argb, rect.width(), px0 - x0, py0 - y0, px1 - px0, py1 - py0, px0, py0));
            t = newer.nextSetBit(runEnd);
        }
        return patches;
    }

    private static DecodedTiles.Patch subPatch(int[] argb, int stride, int sx, int sy, int w, int h, int x, int y) {
        int[] part = new int[w * h];
        for (int r = 0; r < h; r++) {
            System.arraycopy(argb, (sy + r) * stride + sx, part, r * w, w);
        }
        return new DecodedTiles.Patch(x, y, w, h, part);
    }

    /** Hands the collected batch to the UI; the rectangles are reported once the sink returned. */
    private void flush() {
        DecodedTiles batch = new DecodedTiles(batchSequence, streamWidth, streamHeight, List.copyOf(batchPatches),
                batchBytes);
        List<Long> rects = List.copyOf(batchRects);
        batchPatches.clear();
        batchRects.clear();
        batchBytes = 0;
        batchPixels = 0;
        if (!awaitPostSlot()) {
            return; // closed: nothing is reported any more
        }
        try {
            deliverOn.execute(() -> deliver(batch, rects));
        } catch (RuntimeException e) {
            releasePostSlot();
            LOG.log(Level.WARNING, "Cannot hand a UDP batch over to the UI", e);
            if (!closed) {
                rects.forEach(outcomes::dropped);
            }
        }
    }

    /**
     * Decoder thread: waits until fewer than {@link #MAX_POSTED_BATCHES} batches are posted, then takes a slot.
     *
     * @return {@code false} when closed (or interrupted, which only {@link #close()} does)
     */
    private boolean awaitPostSlot() {
        synchronized (postedLock) {
            try {
                while (posted >= MAX_POSTED_BATCHES && !closed) {
                    waitingForUi = true;
                    postedLock.wait();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            } finally {
                waitingForUi = false;
            }
            if (closed) {
                return false;
            }
            posted++;
            return true;
        }
    }

    private void releasePostSlot() {
        synchronized (postedLock) {
            posted--;
            postedLock.notifyAll();
        }
    }

    /** UI thread: a posted batch runs; its slot is released whatever happens. */
    private void deliver(DecodedTiles batch, List<Long> rects) {
        try {
            apply(batch, rects);
        } finally {
            releasePostSlot();
        }
    }

    private void apply(DecodedTiles batch, List<Long> rects) {
        if (closed || failed.get()) {
            return;
        }
        boolean taken;
        try {
            taken = sink.test(batch);
        } catch (RuntimeException | Error e) {
            // The batch is a delta: a view that failed to apply it stays damaged, as on v2.
            if (failed.compareAndSet(false, true)) {
                LOG.log(Level.ERROR, "Applying UDP batch " + batch.sequence() + " failed, ending the stream", e);
                onCorrupt.accept(CORRUPT_REASON);
            }
            return;
        }
        if (taken) {
            rects.forEach(outcomes::applied);
        } else {
            rects.forEach(outcomes::dropped);
        }
    }

    /** Decoder thread: ends the stream once; the callback runs on the UI executor. */
    private void corrupt(String message, Throwable cause) {
        if (!failed.compareAndSet(false, true)) {
            return;
        }
        batchPatches.clear();
        batchRects.clear();
        LOG.log(Level.WARNING, message, cause);
        try {
            deliverOn.execute(() -> {
                if (!closed) {
                    onCorrupt.accept(CORRUPT_REASON);
                }
            });
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Cannot report a corrupt UDP stream to the UI", e);
        }
    }
}

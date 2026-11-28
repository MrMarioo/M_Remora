package pl.mtech.mremora.viewer;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import pl.mtech.mremora.protocol.RectPixels;
import pl.mtech.mremora.protocol.ScreenInfo;
import pl.mtech.mremora.protocol.udp.CompleteRect;
import pl.mtech.mremora.protocol.udp.TileFragment;

class UdpTileDecoderTest {

    private static final Duration WAIT = Duration.ofSeconds(5);
    private static final ScreenInfo LARGEST_SCREEN = new ScreenInfo(65535, 65535, 100);

    private final List<DecodedTiles> received = new CopyOnWriteArrayList<>();
    private final List<Long> applied = new CopyOnWriteArrayList<>();
    private final List<Long> dropped = new CopyOnWriteArrayList<>();
    private final List<String> corrupt = new CopyOnWriteArrayList<>();
    private UdpTileDecoder decoder;

    @AfterEach
    void tearDown() {
        if (decoder != null) {
            decoder.close();
        }
    }

    private void start() {
        start(() -> LARGEST_SCREEN);
    }

    /** The "UI" runs inline on the decoder thread and takes every batch. */
    private void start(Supplier<ScreenInfo> screen) {
        start(screen, received::add);
    }

    private void start(Supplier<ScreenInfo> screen, Predicate<DecodedTiles> sink) {
        start(screen, Runnable::run, sink);
    }

    private void start(Supplier<ScreenInfo> screen, Executor ui, Predicate<DecodedTiles> sink) {
        decoder = new UdpTileDecoder(screen, ui, sink, new UdpTileDecoder.Outcomes() {
            @Override
            public void applied(long rectId) {
                applied.add(rectId);
            }

            @Override
            public void dropped(long rectId) {
                dropped.add(rectId);
            }
        }, corrupt::add);
    }

    /** Blocks the decoder thread (in the screen supplier) until {@code release} opens. */
    private static Supplier<ScreenInfo> heldUntil(CountDownLatch release) {
        return () -> {
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return LARGEST_SCREEN;
        };
    }

    private static CompleteRect solid(int epoch, int sw, int sh, long seq, long id, int x, int y, int w, int h,
                                      int rgb) {
        return new CompleteRect(epoch, sw, sh, seq, id, x, y, w, h, TileFragment.SOLID, RectPixels.encodeSolid(rgb));
    }

    private boolean ended(int count) throws InterruptedException {
        return poll(() -> applied.size() + dropped.size() >= count, WAIT);
    }

    /** {@code count} solid rects side by side in one tile row, each its own tile and capture. */
    private void offerRow(int count) {
        for (int i = 0; i < count; i++) {
            decoder.offer(solid(0, count * 64, 64, i + 1, i + 1, i * 64, 0, 64, 64, 0x010101 * (i + 1)));
        }
    }

    @Test
    void stalledUiHoldsAtMostTwoBatches() throws Exception {
        LinkedBlockingQueue<Runnable> uiTasks = new LinkedBlockingQueue<>(); // a UI thread that does not run
        start(() -> LARGEST_SCREEN, uiTasks::add, received::add);
        int count = 100;

        offerRow(count);

        assertThat(poll(decoder::waitingForUi, WAIT)).as("decoder waits for the UI").isTrue();
        assertThat(uiTasks).hasSize(UdpTileDecoder.MAX_POSTED_BATCHES);
        assertThat(decoder.postedBatches()).isEqualTo(UdpTileDecoder.MAX_POSTED_BATCHES);
        assertThat(applied).isEmpty(); // nothing ran on the UI yet

        // The UI resumes: everything is applied, never more than two batches waiting.
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (applied.size() < count && System.nanoTime() - deadline < 0) {
            assertThat(uiTasks.size()).isLessThanOrEqualTo(UdpTileDecoder.MAX_POSTED_BATCHES);
            Runnable task = uiTasks.poll(10, TimeUnit.MILLISECONDS);
            if (task != null) {
                task.run();
            }
        }
        assertThat(applied).hasSize(count);
        assertThat(dropped).isEmpty();
        assertThat(received.stream().mapToInt(t -> t.patches().size()).sum()).isEqualTo(count);
        assertThat(decoder.postedBatches()).isZero();
    }

    @Test
    void closeReleasesADecoderWaitingForTheUi() throws Exception {
        LinkedBlockingQueue<Runnable> uiTasks = new LinkedBlockingQueue<>();
        start(() -> LARGEST_SCREEN, uiTasks::add, received::add);
        offerRow(100);
        assertThat(poll(decoder::waitingForUi, WAIT)).isTrue();

        decoder.close();

        assertThat(poll(decoder::stopped, WAIT)).as("decoder thread stopped").isTrue();
        uiTasks.forEach(Runnable::run); // late UI tasks after close report nothing
        assertThat(applied).isEmpty();
        assertThat(dropped).isEmpty();
        assertThat(decoder.postedBatches()).isZero();
    }

    @Test
    void appliesNewerOnly() throws Exception {
        start();
        decoder.offer(solid(0, 128, 64, 5, 1, 0, 0, 64, 64, 0xFF0000));
        // Older capture over both tiles: only the right tile (still never painted) is newer.
        decoder.offer(solid(0, 128, 64, 3, 2, 0, 0, 128, 64, 0x0000FF));

        assertThat(ended(2)).isTrue();
        assertThat(applied).containsExactly(1L, 2L);
        List<DecodedTiles.Patch> patches = received.stream().flatMap(t -> t.patches().stream()).toList();
        assertThat(patches).hasSize(2);
        assertThat(patches.get(0)).satisfies(p -> {
            assertThat(List.of(p.x(), p.y(), p.width(), p.height())).containsExactly(0, 0, 64, 64);
            assertThat(p.argb()).containsOnly(0xFFFF0000);
        });
        assertThat(patches.get(1)).satisfies(p -> {
            assertThat(List.of(p.x(), p.y(), p.width(), p.height())).containsExactly(64, 0, 64, 64);
            assertThat(p.argb()).hasSize(64 * 64).containsOnly(0xFF0000FF);
        });
    }

    @Test
    void subRectanglesFollowTheRectNotTheTile() throws Exception {
        start();
        // 100x70 stream: edge tiles are 36 wide / 6 high. Tile (1,0) gets a newer version first.
        decoder.offer(solid(0, 100, 70, 9, 1, 64, 0, 36, 64, 0x00FF00));
        assertThat(ended(1)).isTrue();
        int[] lossless = new int[90 * 60];
        for (int i = 0; i < lossless.length; i++) {
            lossless[i] = 0xFF000000 | i;
        }
        decoder.offer(new CompleteRect(0, 100, 70, 4, 2, 5, 8, 90, 60, TileFragment.LOSSLESS,
                RectPixels.encodeLossless(lossless, 90, 0, 0, 90, 60)));

        assertThat(ended(2)).isTrue();
        assertThat(applied).containsExactly(1L, 2L);
        DecodedTiles.Patch p = received.getLast().patches().getFirst();
        assertThat(received.getLast().patches()).hasSize(2); // one run of newer tiles per tile row
        // Row 0: only the left tile (x 5..63, y 8..63); row 1: both tiles (x 5..94, y 64..67).
        assertThat(List.of(p.x(), p.y(), p.width(), p.height())).containsExactly(5, 8, 59, 56);
        assertThat(p.argb()[0]).isEqualTo(lossless[0]);
        assertThat(p.argb()[59]).isEqualTo(lossless[90]); // second row of the patch = second row of the rect
        DecodedTiles.Patch q = received.getLast().patches().get(1);
        assertThat(List.of(q.x(), q.y(), q.width(), q.height())).containsExactly(5, 64, 90, 4);
        assertThat(q.argb()[0]).isEqualTo(lossless[56 * 90]);
    }

    @Test
    void skippedRectReportedApplied() throws Exception {
        start();
        decoder.offer(solid(0, 128, 64, 5, 1, 0, 0, 128, 64, 0xFF0000));
        decoder.offer(solid(0, 128, 64, 4, 2, 0, 0, 128, 64, 0x00FF00));
        decoder.offer(solid(0, 128, 64, 5, 3, 0, 0, 64, 64, 0x00FF00)); // same capture again: nothing newer

        assertThat(ended(3)).isTrue();
        assertThat(applied).containsExactlyInAnyOrder(1L, 2L, 3L);
        assertThat(dropped).isEmpty();
        assertThat(received.stream().mapToInt(t -> t.patches().size()).sum()).isEqualTo(1);
    }

    @Test
    void newEpochNewSize() throws Exception {
        start();
        decoder.offer(solid(0, 128, 64, 5, 1, 0, 0, 128, 64, 0xFF0000));
        // A new epoch has new versions: a lower captureSeq is not "older" there.
        decoder.offer(solid(1, 64, 32, 1, 2, 0, 0, 64, 32, 0x00FF00));
        decoder.offer(solid(0, 128, 64, 6, 3, 0, 0, 128, 64, 0x0000FF)); // late rect of the old epoch
        decoder.offer(solid(1, 128, 64, 2, 4, 0, 0, 64, 32, 0x0000FF)); // same epoch, other size: broken

        assertThat(ended(4)).isTrue();
        assertThat(applied).containsExactly(1L, 2L);
        assertThat(dropped).containsExactly(3L, 4L);
        DecodedTiles last = received.getLast();
        assertThat(List.of(last.streamWidth(), last.streamHeight())).containsExactly(64, 32);
        assertThat(last.patches()).singleElement().satisfies(p -> assertThat(p.argb()).containsOnly(0xFF00FF00));
    }

    @Test
    void corruptRectDroppedSessionContinues() throws Exception {
        start(() -> new ScreenInfo(256, 128, 100));
        decoder.offer(new CompleteRect(0, 128, 64, 1, 1, 0, 0, 64, 64, TileFragment.LOSSLESS,
                new byte[] {1, 2, 3, 4, 5, 6, 7, 8}));
        decoder.offer(new CompleteRect(0, 128, 64, 2, 2, 0, 0, 64, 64, TileFragment.JPEG, jpegBytes(32, 32)));
        decoder.offer(new CompleteRect(0, 128, 64, 4, 4, 0, 0, 64, 64, 7, new byte[4])); // unknown encoding
        decoder.offer(solid(0, 128, 64, 5, 5, 100, 0, 64, 64, 0)); // outside the stream
        decoder.offer(new CompleteRect(0, 128, 64, 6, 6, 0, 0, 64, 64, TileFragment.JPEG, jpegBytes(64, 64)));

        assertThat(ended(5)).isTrue();
        assertThat(dropped).containsExactly(1L, 2L, 4L, 5L);
        assertThat(corrupt).isEmpty();
        assertThat(applied).containsExactly(6L);
        // The failed rects claimed nothing: the decoded one is painted although its tile was "seen" before.
        assertThat(received.getLast().patches()).singleElement()
                .satisfies(p -> assertThat(p.argb()).hasSize(64 * 64));
    }

    @Test
    void streamLargerThanTheScreenEndsTheStream() throws Exception {
        start(() -> new ScreenInfo(256, 128, 100));

        decoder.offer(solid(0, 512, 64, 3, 3, 0, 0, 64, 64, 0)); // the agent never upscales

        assertThat(poll(() -> !corrupt.isEmpty(), WAIT)).isTrue();
        decoder.offer(solid(0, 128, 64, 4, 4, 0, 0, 64, 64, 0)); // nothing is decoded afterwards
        Thread.sleep(200);
        assertThat(corrupt).containsExactly("protocol error");
        assertThat(received).isEmpty();
        assertThat(applied).isEmpty();
        assertThat(dropped).isEmpty();
    }

    @Test
    void failingUiEndsTheStream() throws Exception {
        start(() -> LARGEST_SCREEN, t -> {
            throw new IllegalStateException("view broken");
        });

        decoder.offer(solid(0, 128, 64, 1, 1, 0, 0, 64, 64, 0));

        assertThat(poll(() -> !corrupt.isEmpty(), WAIT)).isTrue();
        decoder.offer(solid(0, 128, 64, 2, 2, 64, 0, 64, 64, 0));
        Thread.sleep(200);
        assertThat(corrupt).containsExactly("protocol error");
        assertThat(applied).isEmpty();
        assertThat(dropped).isEmpty();
    }

    @Test
    void batchNotTakenIsDroppedAndNotPainted() throws Exception {
        AtomicBoolean viewReady = new AtomicBoolean();
        start(() -> LARGEST_SCREEN, t -> viewReady.get() && received.add(t));

        decoder.offer(solid(0, 128, 64, 1, 1, 0, 0, 128, 64, 0xFF0000)); // claimed, then not taken by the UI
        assertThat(ended(1)).isTrue();
        assertThat(dropped).containsExactly(1L);
        assertThat(received).isEmpty();

        viewReady.set(true);
        decoder.offer(solid(0, 128, 64, 2, 2, 0, 0, 128, 64, 0x00FF00)); // the agent's resend: a newer capture

        assertThat(ended(2)).isTrue();
        assertThat(applied).containsExactly(2L);
        assertThat(corrupt).isEmpty();
        assertThat(received).singleElement().satisfies(t -> assertThat(t.patches()).singleElement()
                .satisfies(p -> assertThat(p.argb()).containsOnly(0xFF00FF00)));
    }

    @Test
    void flushesEverySixteenRects() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        start(heldUntil(release));
        for (int i = 0; i < 20; i++) {
            decoder.offer(solid(0, 64 * 20, 64, 1 + i, 1 + i, 64 * i, 0, 64, 64, 0));
        }

        release.countDown();

        assertThat(ended(20)).isTrue();
        assertThat(received).extracting(t -> t.patches().size()).containsExactly(16, 4);
        assertThat(applied).hasSize(20);
    }

    @Test
    void batchAreaIsCapped() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        start(heldUntil(release));
        for (int i = 0; i < 3; i++) { // each covers the whole 128x64 stream with a newer capture
            decoder.offer(solid(0, 128, 64, 1 + i, 1 + i, 0, 0, 128, 64, 0x111111 * (i + 1)));
        }

        release.countDown();

        assertThat(ended(3)).isTrue();
        // 2x the stream area fits; the third rect starts the next batch instead of being dropped.
        assertThat(received).extracting(t -> t.patches().size()).containsExactly(2, 1);
        assertThat(received).extracting(DecodedTiles::sequence).containsExactly(2L, 3L);
        assertThat(applied).containsExactly(1L, 2L, 3L);
        assertThat(dropped).isEmpty();
    }

    @Test
    void batchesWhenQueueBusy() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        start(heldUntil(release)); // the decoder thread is busy while all rects are queued
        for (int i = 0; i < 10; i++) {
            decoder.offer(solid(0, 640, 64, 7 + i, 1 + i, 64 * i, 0, 64, 64, 0x101010 * i));
        }

        release.countDown();

        assertThat(ended(10)).isTrue();
        assertThat(received).hasSizeLessThanOrEqualTo(2);
        assertThat(received.stream().mapToInt(t -> t.patches().size()).sum()).isEqualTo(10);
        assertThat(received.getLast().sequence()).isEqualTo(16);
        assertThat(applied).hasSize(10);
    }

    @Test
    void closeStopsDelivery() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        start(heldUntil(release));
        decoder.offer(solid(0, 128, 64, 1, 1, 0, 0, 64, 64, 0));

        decoder.close();
        release.countDown();
        decoder.offer(solid(0, 128, 64, 2, 2, 0, 0, 64, 64, 0));

        Thread.sleep(200);
        assertThat(received).isEmpty();
        assertThat(applied).isEmpty();
    }

    private static byte[] jpegBytes(int w, int h) throws Exception {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.ORANGE);
        g.fillRect(0, 0, w, h);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertThat(ImageIO.write(img, "jpeg", out)).isTrue();
        return out.toByteArray();
    }

    private static boolean poll(BooleanSupplier condition, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() >= deadline) {
                return false;
            }
            Thread.sleep(5);
        }
        return true;
    }
}

package pl.mtech.mremora.viewer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import pl.mtech.mremora.protocol.RectPixels;
import pl.mtech.mremora.protocol.ScreenInfo;
import pl.mtech.mremora.protocol.TileUpdate;
import pl.mtech.mremora.protocol.TileUpdate.Encoding;
import pl.mtech.mremora.protocol.TileUpdate.Rect;

class TileDecoderTest {

    /** No stream is too large for it, so tests not about the screen bound are not affected by it. */
    private static final ScreenInfo LARGEST_SCREEN = new ScreenInfo(65535, 65535, 100);

    private final List<DecodedTiles> received = new CopyOnWriteArrayList<>();
    private final List<String> corrupt = new CopyOnWriteArrayList<>();
    private TileDecoder decoder;

    @AfterEach
    void tearDown() {
        if (decoder != null) {
            decoder.close();
        }
    }

    /** Delivery goes through a latch so tests wait for the decoder thread deterministically. */
    private CountDownLatch start(int expectedEvents) {
        return start(expectedEvents, LARGEST_SCREEN);
    }

    private CountDownLatch start(int expectedEvents, ScreenInfo screen) {
        CountDownLatch latch = new CountDownLatch(expectedEvents);
        decoder = new TileDecoder(() -> screen, Runnable::run, f -> {
            received.add(f);
            latch.countDown();
        }, reason -> {
            corrupt.add(reason);
            latch.countDown();
        });
        return latch;
    }

    private static void await(CountDownLatch latch) throws InterruptedException {
        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
    }

    private static Rect solid(int x, int y, int w, int h, int rgb) {
        return new Rect(x, y, w, h, Encoding.SOLID, RectPixels.encodeSolid(rgb));
    }

    private static TileUpdate part(long seq, int sw, int sh, boolean last, Rect... rects) {
        return new TileUpdate(seq, sw, sh, last, 0, List.of(rects));
    }

    @Test
    void partsAreJoinedUntilLastPart() throws Exception {
        CountDownLatch done = start(1);
        decoder.offer(part(3, 128, 64, false, solid(0, 0, 64, 64, 0x112233)));
        decoder.offer(part(3, 128, 64, true, solid(64, 0, 64, 64, 0x445566)));
        await(done);
        assertThat(received).hasSize(1);
        DecodedTiles t = received.getFirst();
        assertThat(t.sequence()).isEqualTo(3);
        assertThat(t.patches()).extracting(DecodedTiles.Patch::x).containsExactly(0, 64);
    }

    @Test
    void framesAreNotSkipped() throws Exception {
        CountDownLatch done = start(10);
        for (int i = 0; i < 10; i++) {
            decoder.offer(part(i, 64, 64, true, solid(0, 0, 64, 64, i)));
        }
        await(done);
        assertThat(received).extracting(DecodedTiles::sequence).containsExactly(0L, 1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L);
    }

    @Test
    void solidBecomesAFilledPatch() throws Exception {
        CountDownLatch done = start(1);
        decoder.offer(part(0, 64, 64, true, solid(8, 4, 3, 2, 0x336699)));
        await(done);
        DecodedTiles.Patch p = received.getFirst().patches().getFirst();
        assertThat(p.width()).isEqualTo(3);
        assertThat(p.height()).isEqualTo(2);
        assertThat(p.argb()).hasSize(6).containsOnly(0xFF336699);
    }

    @Test
    void losslessAndJpegDecode() throws Exception {
        int[] pixels = new int[64 * 64];
        for (int i = 0; i < pixels.length; i++) {
            pixels[i] = (i % 7) * 0x101010;
        }
        Rect lossless = new Rect(0, 0, 64, 64, Encoding.LOSSLESS, RectPixels.encodeLossless(pixels, 64, 0, 0, 64, 64));
        Rect jpeg = new Rect(64, 0, 64, 64, Encoding.JPEG, jpegBytes(64, 64));
        CountDownLatch done = start(1);
        decoder.offer(part(0, 128, 64, true, lossless, jpeg));
        await(done);
        List<DecodedTiles.Patch> patches = received.getFirst().patches();
        for (int i = 0; i < pixels.length; i++) {
            assertThat(patches.get(0).argb()[i]).isEqualTo(0xFF000000 | pixels[i]);
        }
        int px = patches.get(1).argb()[0];
        assertThat((px >>> 24) & 0xFF).isEqualTo(0xFF);
        assertThat((px >> 16) & 0xFF).isCloseTo(0x33, within(10));
        assertThat((px >> 8) & 0xFF).isCloseTo(0x66, within(10));
        assertThat(px & 0xFF).isCloseTo(0xCC, within(10));
        assertThat(patches.get(1).argb()).hasSize(64 * 64);
    }

    @Test
    void jpegOfWrongSizeIsCorrupt() throws Exception {
        CountDownLatch done = start(1);
        decoder.offer(part(0, 128, 64, true, new Rect(0, 0, 64, 64, Encoding.JPEG, jpegBytes(32, 32))));
        await(done);
        assertThat(corrupt).containsExactly("protocol error");
        assertThat(received).isEmpty();
    }

    @Test
    void badLosslessIsCorrupt() throws Exception {
        CountDownLatch done = start(1);
        decoder.offer(part(0, 64, 64, true, new Rect(0, 0, 64, 64, Encoding.LOSSLESS, new byte[]{1, 2, 3})));
        await(done);
        assertThat(corrupt).containsExactly("protocol error");
        assertThat(received).isEmpty();
    }

    @Test
    void hugeRectWithBadDataIsCorruptNotACrash() throws Exception {
        CountDownLatch done = start(1);
        decoder.offer(part(0, 65535, 65535, true, new Rect(0, 0, 65535, 65535, Encoding.LOSSLESS, new byte[]{1, 2})));
        await(done);
        assertThat(corrupt).containsExactly("protocol error");
    }

    @Test
    void seqChangeBeforeLastPartIsCorrupt() throws Exception {
        CountDownLatch done = start(1);
        decoder.offer(part(1, 64, 64, false, solid(0, 0, 64, 64, 1)));
        decoder.offer(part(2, 64, 64, true, solid(0, 0, 64, 64, 2)));
        await(done);
        assertThat(corrupt).containsExactly("protocol error");
        assertThat(received).isEmpty();
    }

    @Test
    void nothingIsDeliveredAfterCorrupt() throws Exception {
        // Frame 0 is delivered inline on the decoder thread and parks it in the sink, so the corrupt part and the
        // two valid parts behind it are all accepted into the queue before any of them is processed.
        CountDownLatch inSink = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        decoder = new TileDecoder(() -> LARGEST_SCREEN, Runnable::run, f -> {
            received.add(f);
            inSink.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, corrupt::add);
        decoder.offer(part(0, 64, 64, true, solid(0, 0, 64, 64, 1)));
        await(inSink);
        decoder.offer(part(1, 64, 64, true, new Rect(0, 0, 64, 64, Encoding.LOSSLESS, new byte[]{1})));
        decoder.offer(part(2, 64, 64, true, solid(0, 0, 64, 64, 2)));
        decoder.offer(part(3, 64, 64, true, solid(0, 0, 64, 64, 3)));
        release.countDown();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (decoder.partsProcessed() < 4) {
            assertThat(System.nanoTime()).isLessThan(deadline);
            Thread.onSpinWait();
        }
        assertThat(corrupt).containsExactly("protocol error");
        assertThat(received).extracting(DecodedTiles::sequence).containsExactly(0L);
    }

    @Test
    void oversizedRectIsCorrupt() throws Exception {
        CountDownLatch done = start(1);
        decoder.offer(part(0, 4097, 4097, true, solid(0, 0, 4097, 4097, 1)));
        await(done);
        assertThat(corrupt).containsExactly("protocol error");
        assertThat(received).isEmpty();
    }

    @Test
    void streamSizeChangeBetweenFramesIsFine() throws Exception {
        CountDownLatch done = start(2);
        decoder.offer(part(0, 64, 32, true, solid(0, 0, 64, 32, 1)));
        decoder.offer(part(1, 32, 16, true, solid(0, 0, 32, 16, 2)));
        await(done);
        assertThat(received).extracting(DecodedTiles::streamWidth).containsExactly(64, 32);
        assertThat(received).extracting(DecodedTiles::streamHeight).containsExactly(32, 16);
        assertThat(corrupt).isEmpty();
    }

    @Test
    void streamSizeChangeInsideFrameIsCorrupt() throws Exception {
        CountDownLatch done = start(1);
        decoder.offer(part(0, 64, 64, false, solid(0, 0, 64, 64, 1)));
        decoder.offer(part(0, 32, 32, true, solid(0, 0, 32, 32, 2)));
        await(done);
        assertThat(corrupt).containsExactly("protocol error");
    }

    @Test
    void nothingAfterClose() throws Exception {
        AtomicInteger delivered = new AtomicInteger();
        // The executor parks deliveries until the test releases them, after close().
        List<Runnable> parked = new CopyOnWriteArrayList<>();
        decoder = new TileDecoder(() -> LARGEST_SCREEN, parked::add, f -> delivered.incrementAndGet(), r -> delivered.incrementAndGet());
        decoder.offer(part(0, 64, 64, true, solid(0, 0, 64, 64, 1)));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (true) {
            if (!parked.isEmpty()) {
                break;
            }
            assertThat(System.nanoTime()).isLessThan(deadline);
            Thread.onSpinWait();
        }
        decoder.close();
        parked.forEach(Runnable::run);
        decoder.offer(part(1, 64, 64, true, solid(0, 0, 64, 64, 1)));
        assertThat(delivered.get()).isZero();
    }

    @Test
    void streamLargerThanTheScreenIsCorrupt() throws Exception {
        CountDownLatch done = start(1, new ScreenInfo(64, 64, 100));
        decoder.offer(part(0, 65, 64, true, solid(0, 0, 8, 8, 1)));
        await(done);
        assertThat(corrupt).containsExactly("protocol error");
        assertThat(received).isEmpty();
    }

    @Test
    void streamOfExactlyTheScreenSizeIsFine() throws Exception {
        CountDownLatch done = start(1, new ScreenInfo(64, 32, 100));
        decoder.offer(part(0, 64, 32, true, solid(0, 0, 64, 32, 1)));
        await(done);
        assertThat(received).hasSize(1);
        assertThat(corrupt).isEmpty();
    }

    @Test
    void frameCoveringTheStreamTwiceIsFine() throws Exception {
        CountDownLatch done = start(1);
        decoder.offer(part(0, 64, 64, false, solid(0, 0, 64, 64, 1)));
        decoder.offer(part(0, 64, 64, true, solid(0, 0, 64, 64, 2)));
        await(done);
        assertThat(received).hasSize(1);
        assertThat(corrupt).isEmpty();
    }

    @Test
    void frameCoveringMoreThanTwiceTheStreamIsCorrupt() throws Exception {
        CountDownLatch done = start(1);
        decoder.offer(part(0, 64, 64, false, solid(0, 0, 64, 64, 1)));
        decoder.offer(part(0, 64, 64, false, solid(0, 0, 64, 64, 2)));
        decoder.offer(part(0, 64, 64, true, solid(0, 0, 1, 1, 3)));
        await(done);
        assertThat(corrupt).containsExactly("protocol error");
        assertThat(received).isEmpty();
        assertThat(decoder.heldPatches()).as("patches of the broken frame released").isZero();
    }

    @Test
    void pixelBudgetStartsOverWithEachFrame() throws Exception {
        CountDownLatch done = start(3);
        for (int i = 0; i < 3; i++) {
            decoder.offer(part(i, 64, 64, false, solid(0, 0, 64, 64, 1)));
            decoder.offer(part(i, 64, 64, true, solid(0, 0, 64, 64, 2)));
        }
        await(done);
        assertThat(received).extracting(DecodedTiles::sequence).containsExactly(0L, 1L, 2L);
        assertThat(corrupt).isEmpty();
    }

    @Test
    void jpegWithLargerDeclaredImageIsCorrupt() throws Exception {
        CountDownLatch done = start(1);
        decoder.offer(part(0, 128, 64, true, new Rect(0, 0, 32, 32, Encoding.JPEG, jpegBytes(64, 64))));
        await(done);
        assertThat(corrupt).containsExactly("protocol error");
        assertThat(received).isEmpty();
    }

    @Test
    void failingSinkErrorDoesNotKillTheDecoder() throws Exception {
        CountDownLatch second = new CountDownLatch(1);
        decoder = new TileDecoder(() -> LARGEST_SCREEN, Runnable::run, f -> {
            if (f.sequence() == 0) {
                throw new AssertionError("view broken");
            }
            received.add(f);
            second.countDown();
        }, corrupt::add);
        decoder.offer(part(0, 64, 64, true, solid(0, 0, 64, 64, 1)));
        decoder.offer(part(1, 64, 64, true, solid(0, 0, 64, 64, 2)));
        await(second);
        assertThat(received).extracting(DecodedTiles::sequence).containsExactly(1L);
    }

    @Test
    void encodedBytesSumAllParts() throws Exception {
        Rect a = solid(0, 0, 64, 64, 1);
        Rect b = new Rect(64, 0, 64, 64, Encoding.JPEG, jpegBytes(64, 64));
        CountDownLatch done = start(1);
        decoder.offer(part(0, 128, 64, false, a));
        decoder.offer(part(0, 128, 64, true, b));
        await(done);
        assertThat(received.getFirst().encodedBytes()).isEqualTo(a.dataLength() + b.dataLength());
    }

    private static byte[] jpegBytes(int w, int h) throws Exception {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_3BYTE_BGR);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(0x3366CC));
        g.fillRect(0, 0, w, h);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertThat(ImageIO.write(img, "jpeg", out)).isTrue();
        return out.toByteArray();
    }
}

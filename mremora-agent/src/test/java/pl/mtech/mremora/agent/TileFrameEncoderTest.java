package pl.mtech.mremora.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;
import pl.mtech.mremora.protocol.RectPixels;
import pl.mtech.mremora.protocol.StreamConfig;
import pl.mtech.mremora.protocol.TileUpdate;
import pl.mtech.mremora.protocol.TileUpdate.Encoding;

class TileFrameEncoderTest {

    private static BufferedImage flat(int w, int h, int rgb) {
        var img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                img.setRGB(x, y, rgb);
            }
        }
        return img;
    }

    private static BufferedImage noise(int w, int h, long seed) {
        var r = new Random(seed);
        var img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                img.setRGB(x, y, r.nextInt(0x1000000));
            }
        }
        return img;
    }

    /** Few-color pattern: SOLID and LOSSLESS tiles only. */
    private static BufferedImage pattern(int w, int h) {
        var img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                img.setRGB(x, y, ((x / 5 + y / 7) % 3) * 0x333333 + (x < 70 ? 0 : 0x0000FF));
            }
        }
        return img;
    }

    private static long area(List<TileUpdate> parts) {
        long sum = 0;
        for (var p : parts) {
            for (var r : p.rects()) {
                sum += (long) r.width() * r.height();
            }
        }
        return sum;
    }

    private static int partBytes(TileUpdate p) {
        int sum = 17;
        for (var r : p.rects()) {
            sum += 13 + r.dataLength();
        }
        return sum;
    }

    /** Applies parts to a viewer-side copy, decoding JPEG with ImageIO. */
    private static int[] apply(int[] viewer, List<TileUpdate> parts) throws Exception {
        for (var p : parts) {
            int w = p.streamWidth();
            if (viewer == null || viewer.length != w * p.streamHeight()) {
                viewer = new int[w * p.streamHeight()];
            }
            for (var r : p.rects()) {
                int[] px = switch (r.encoding()) {
                    case SOLID -> {
                        int[] a = new int[r.width() * r.height()];
                        java.util.Arrays.fill(a, RectPixels.decodeSolid(r.data()));
                        yield a;
                    }
                    case LOSSLESS -> RectPixels.decodeLossless(r.data(), r.width(), r.height());
                    case JPEG -> {
                        var img = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(r.data()));
                        assertThat(img.getWidth()).isEqualTo(r.width());
                        assertThat(img.getHeight()).isEqualTo(r.height());
                        yield img.getRGB(0, 0, r.width(), r.height(), null, 0, r.width());
                    }
                    case COPY -> throw new AssertionError();
                };
                for (int y = 0; y < r.height(); y++) {
                    System.arraycopy(px, y * r.width(), viewer, (r.y() + y) * w + r.x(), r.width());
                }
            }
        }
        return viewer;
    }

    private static int[] rgb(BufferedImage img) {
        int[] a = img.getRGB(0, 0, img.getWidth(), img.getHeight(), null, 0, img.getWidth());
        for (int i = 0; i < a.length; i++) {
            a[i] |= 0xFF000000;
        }
        return a;
    }

    @Test
    void firstFrameCoversTheWholeStream() {
        var enc = new TileFrameEncoder(new MutableScreen(pattern(200, 100)));
        var parts = enc.next();
        assertThat(area(parts)).isEqualTo(200L * 100);
        assertThat(parts.getLast().lastPart()).isTrue();
    }

    @Test
    void unchangedScreenSendsNothing() {
        var enc = new TileFrameEncoder(new MutableScreen(pattern(200, 100)));
        enc.next();
        assertThat(enc.next()).isEmpty();
    }

    @Test
    void sequenceGrowsOnlyOnNonEmptyFrames() {
        var screen = new MutableScreen(flat(200, 100, 0x112233));
        var enc = new TileFrameEncoder(screen);
        assertThat(enc.next().getFirst().sequence()).isEqualTo(0);
        assertThat(enc.next()).isEmpty();
        screen.set(flat(200, 100, 0x445566));
        assertThat(enc.next().getFirst().sequence()).isEqualTo(1);
    }

    @Test
    void onlyTheChangedTileIsSent() {
        var img = flat(200, 100, 0x112233);
        var screen = new MutableScreen(img);
        var enc = new TileFrameEncoder(screen);
        enc.next();
        var changed = flat(200, 100, 0x112233);
        changed.setRGB(70, 10, 0xFFFFFF);
        screen.set(changed);
        var parts = enc.next();
        assertThat(parts).hasSize(1);
        assertThat(parts.getFirst().rects()).hasSize(1);
        var r = parts.getFirst().rects().getFirst();
        assertThat(r.x()).isEqualTo(64);
        assertThat(r.y()).isEqualTo(0);
        assertThat(r.width()).isEqualTo(64);
        assertThat(r.height()).isEqualTo(64);
    }

    @Test
    void jpegTilesBecomeCleanAfterSending() {
        var enc = new TileFrameEncoder(new MutableScreen(noise(200, 100, 1)));
        var first = enc.next();
        assertThat(first.stream().flatMap(p -> p.rects().stream()).anyMatch(r -> r.encoding() == Encoding.JPEG))
                .isTrue();
        assertThat(enc.next()).isEmpty();
    }

    @Test
    void configureScalesAndForcesAFullFrame() {
        var enc = new TileFrameEncoder(new MutableScreen(flat(2560, 1440, 0x123456)));
        enc.next();
        enc.configure(new StreamConfig(1600, 900));
        var parts = enc.next();
        assertThat(parts).isNotEmpty();
        assertThat(parts.getFirst().streamWidth()).isEqualTo(1600);
        assertThat(parts.getFirst().streamHeight()).isEqualTo(900);
        assertThat(area(parts)).isEqualTo(1600L * 900);
    }

    @Test
    void partsStayUnderTheLimit() {
        var enc = new TileFrameEncoder(new MutableScreen(noise(2560, 1440, 2)));
        enc.configure(StreamConfig.FULL_RESOLUTION);
        var parts = enc.next();
        assertThat(parts.size()).isGreaterThan(1);
        for (int i = 0; i < parts.size(); i++) {
            assertThat(partBytes(parts.get(i))).isLessThanOrEqualTo(TileFrameEncoder.MAX_PART_BYTES);
            assertThat(parts.get(i).sequence()).isEqualTo(parts.getFirst().sequence());
            assertThat(parts.get(i).lastPart()).isEqualTo(i == parts.size() - 1);
        }
    }

    @Test
    void oneChangedPixelOnALargeScreenSendsOneTile() {
        var screen = new MutableScreen(noise(2560, 1440, 3));
        var enc = new TileFrameEncoder(screen);
        enc.next();
        var img = noise(2560, 1440, 3);
        img.setRGB(5, 5, 0);
        screen.set(img);
        var parts = enc.next();
        assertThat(area(parts)).isEqualTo(64L * 64);
    }

    @Test
    void decodedRectsRebuildTheImage() throws Exception {
        var img = pattern(200, 100);
        var enc = new TileFrameEncoder(new MutableScreen(img));
        int[] viewer = apply(null, enc.next());
        assertThat(viewer).isEqualTo(rgb(img));
    }

    @Test
    void deltasKeepTheViewerCopyExact() throws Exception {
        // Solid/lossless content so the viewer copy is bit-exact.
        var img = pattern(200, 100);
        var screen = new MutableScreen(img);
        var enc = new TileFrameEncoder(screen);
        int[] viewer = apply(null, enc.next());
        assertThat(viewer).isEqualTo(rgb(img));

        var changed = pattern(200, 100);
        changed.setRGB(100, 30, 0xABCDEF);  // interior tile
        changed.setRGB(199, 99, 0x010203);  // edge tile (8x36 corner)
        screen.set(changed);
        viewer = apply(viewer, enc.next());
        assertThat(viewer).isEqualTo(rgb(changed));

        enc.configure(new StreamConfig(100, 50));
        var parts = enc.next();
        assertThat(parts.getFirst().streamWidth()).isEqualTo(100);
        viewer = apply(viewer, parts);
        int[] expected = new FrameScaler().toStreamPixels(changed, new StreamSize(100, 50));
        for (int i = 0; i < expected.length; i++) {
            expected[i] |= 0xFF000000;
        }
        assertThat(viewer).isEqualTo(expected);
    }
}

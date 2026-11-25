package pl.mtech.mremora.viewer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.within;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import pl.mtech.mremora.protocol.FrameUpdate;
import pl.mtech.mremora.protocol.FrameUpdate.Format;

class FrameDecoderTest {

    private static final Duration WAIT = Duration.ofSeconds(5);
    private static final int COLOR = 0x3366CC;

    private final List<DecodedFrame> received = new CopyOnWriteArrayList<>();
    private FrameDecoder decoder;

    @AfterEach
    void tearDown() {
        if (decoder != null) {
            decoder.close();
        }
    }

    @Test
    void decodesSizeAndPixels() throws Exception {
        decoder = new FrameDecoder(Runnable::run, received::add);
        FrameUpdate frame = jpeg(7, 8, 4);

        decoder.offer(frame);

        assertThat(poll(() -> received.size() == 1, WAIT)).isTrue();
        DecodedFrame decoded = received.getFirst();
        assertThat(decoded.sequence()).isEqualTo(7);
        assertThat(decoded.width()).isEqualTo(8);
        assertThat(decoded.height()).isEqualTo(4);
        assertThat(decoded.argb()).hasSize(8 * 4);
        assertThat(decoded.encodedBytes()).isEqualTo(frame.dataLength());
        int pixel = decoded.argb()[0];
        assertThat((pixel >>> 24) & 0xFF).isEqualTo(0xFF);
        assertThat((pixel >> 16) & 0xFF).isCloseTo(0x33, within(8));
        assertThat((pixel >> 8) & 0xFF).isCloseTo(0x66, within(8));
        assertThat(pixel & 0xFF).isCloseTo(0xCC, within(8));
    }

    @Test
    void bgrFastPathKeepsChannelOrder() throws Exception {
        // Four quadrants (red, green / blue, white) catch any swapped channel or row/column mix-up.
        int[][] quadrants = {{0xFF0000, 0x00FF00}, {0x0000FF, 0xFFFFFF}};
        FrameUpdate frame = jpegQuadrants(32, 32, quadrants);
        // ImageIO decodes this JPEG to TYPE_3BYTE_BGR, i.e. the decoder takes its fast path, not getRGB.
        assertThat(ImageIO.read(new ByteArrayInputStream(frame.data())).getType())
                .isEqualTo(BufferedImage.TYPE_3BYTE_BGR);
        decoder = new FrameDecoder(Runnable::run, received::add);

        decoder.offer(frame);

        assertThat(poll(() -> received.size() == 1, WAIT)).isTrue();
        DecodedFrame decoded = received.getFirst();
        for (int qy = 0; qy < 2; qy++) {
            for (int qx = 0; qx < 2; qx++) {
                int expected = quadrants[qy][qx];
                // centre of each quadrant, away from borders where JPEG blurs colours
                int x = qx * 16 + 8;
                int y = qy * 16 + 8;
                int pixel = decoded.argb()[y * decoded.width() + x];
                assertThat((pixel >>> 24) & 0xFF).isEqualTo(0xFF);
                assertThat((pixel >> 16) & 0xFF).as("red at %d,%d", x, y).isCloseTo((expected >> 16) & 0xFF, within(16));
                assertThat((pixel >> 8) & 0xFF).as("green at %d,%d", x, y).isCloseTo((expected >> 8) & 0xFF, within(16));
                assertThat(pixel & 0xFF).as("blue at %d,%d", x, y).isCloseTo(expected & 0xFF, within(16));
            }
        }
    }

    @Test
    void slowUiGetsOnlyLatestFrame() throws Exception {
        List<Runnable> pending = new CopyOnWriteArrayList<>();
        decoder = new FrameDecoder(pending::add, received::add);

        for (long seq = 1; seq <= 3; seq++) {
            decoder.offer(jpeg(seq, 8, 4));
            long expected = seq;
            assertThat(poll(() -> decoder.framesProcessed() == expected, WAIT)).isTrue();
        }

        assertThat(pending).hasSize(1);
        assertThat(received).isEmpty();
        pending.getFirst().run();
        assertThat(received).extracting(DecodedFrame::sequence).containsExactly(3L);
    }

    @Test
    void corruptFrameIsSkipped() throws Exception {
        decoder = new FrameDecoder(Runnable::run, received::add);

        decoder.offer(new FrameUpdate(1, Format.JPEG, new byte[] {1, 2, 3}));
        assertThat(poll(() -> decoder.framesProcessed() == 1, WAIT)).isTrue();
        decoder.offer(jpeg(2, 8, 4));

        assertThat(poll(() -> decoder.framesProcessed() == 2, WAIT)).isTrue();
        assertThat(received).extracting(DecodedFrame::sequence).containsExactly(2L);
    }

    @Test
    void failingDeliveryDoesNotStopTheDecoder() throws Exception {
        AtomicBoolean failNext = new AtomicBoolean(true);
        Executor flaky = task -> {
            if (failNext.getAndSet(false)) {
                throw new IllegalStateException("UI gone");
            }
            task.run();
        };
        decoder = new FrameDecoder(flaky, received::add);

        decoder.offer(jpeg(1, 8, 4));
        assertThat(poll(() -> decoder.framesProcessed() == 1, WAIT)).isTrue();
        decoder.offer(jpeg(2, 8, 4));

        assertThat(poll(() -> received.size() == 1, WAIT)).isTrue();
        assertThat(received).extracting(DecodedFrame::sequence).containsExactly(2L);
    }

    @Test
    void noFrameReachesTheSinkAfterClose() throws Exception {
        List<Runnable> pending = new CopyOnWriteArrayList<>();
        decoder = new FrameDecoder(pending::add, received::add);
        decoder.offer(jpeg(1, 8, 4));
        assertThat(poll(() -> decoder.framesProcessed() == 1, WAIT)).isTrue();

        decoder.close();
        pending.getFirst().run();

        assertThat(received).isEmpty();
    }

    @Test
    void offerAfterCloseIsIgnored() throws Exception {
        decoder = new FrameDecoder(Runnable::run, received::add);
        decoder.close();

        assertThatCode(() -> decoder.offer(jpeg(1, 8, 4))).doesNotThrowAnyException();
        assertThatCode(decoder::close).doesNotThrowAnyException();

        Thread.sleep(200);
        assertThat(received).isEmpty();
    }

    private static FrameUpdate jpeg(long sequence, int width, int height) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(new Color(COLOR));
        g.fillRect(0, 0, width, height);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertThat(ImageIO.write(image, "jpg", out)).isTrue();
        return new FrameUpdate(sequence, Format.JPEG, out.toByteArray());
    }

    private static FrameUpdate jpegQuadrants(int width, int height, int[][] colors) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        for (int qy = 0; qy < 2; qy++) {
            for (int qx = 0; qx < 2; qx++) {
                g.setColor(new Color(colors[qy][qx]));
                g.fillRect(qx * width / 2, qy * height / 2, width / 2, height / 2);
            }
        }
        g.dispose();
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpg").next();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (var stream = new MemoryCacheImageOutputStream(out)) {
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(1.0f);
            writer.setOutput(stream);
            writer.write(null, new IIOImage(image, null, null), param);
        } finally {
            writer.dispose();
        }
        return new FrameUpdate(1, Format.JPEG, out.toByteArray());
    }

    private static boolean poll(BooleanSupplier condition, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() >= deadline) {
                return false;
            }
            Thread.sleep(10);
        }
        return true;
    }
}

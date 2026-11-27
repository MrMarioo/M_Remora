package pl.mtech.mremora.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.Random;
import org.junit.jupiter.api.Test;

class FrameScalerTest {

    private final FrameScaler scaler = new FrameScaler();

    @Test
    void sameSizeReturnsExactPixels() {
        var image = new BufferedImage(7, 5, BufferedImage.TYPE_INT_RGB);
        var random = new Random(42);
        int[] expected = new int[7 * 5];
        for (int y = 0; y < 5; y++) {
            for (int x = 0; x < 7; x++) {
                int rgb = random.nextInt() & 0xFFFFFF;
                image.setRGB(x, y, rgb);
                expected[y * 7 + x] = rgb;
            }
        }
        assertThat(scaler.toStreamPixels(image, new StreamSize(7, 5))).containsExactly(expected);
    }

    @Test
    void intRgbFastPathMasksGarbageTopByte() {
        var image = new BufferedImage(4, 3, BufferedImage.TYPE_INT_RGB);
        int[] data = ((java.awt.image.DataBufferInt) image.getRaster().getDataBuffer()).getData();
        int[] expected = new int[12];
        for (int i = 0; i < 12; i++) {
            data[i] = 0xAB000000 | (i * 0x010203);
            expected[i] = i * 0x010203;
        }
        int[] result = scaler.toStreamPixels(image, new StreamSize(4, 3));
        assertThat(result).containsExactly(expected);
        assertThat(result).isNotSameAs(data);
    }

    @Test
    void halfSizeOfAUniformImageKeepsTheColor() {
        var image = new BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB);
        fill(image, new Color(0x336699));
        int[] pixels = scaler.toStreamPixels(image, new StreamSize(32, 32));
        assertThat(pixels).hasSize(32 * 32).containsOnly(0x336699);
    }

    @Test
    void resultIsAFreshArray() {
        var image = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
        var size = new StreamSize(8, 8);
        assertThat(scaler.toStreamPixels(image, size)).isNotSameAs(scaler.toStreamPixels(image, size));
    }

    @Test
    void acceptsNonRgbCaptures() {
        for (int type : new int[] {BufferedImage.TYPE_INT_ARGB, BufferedImage.TYPE_3BYTE_BGR}) {
            var image = new BufferedImage(4, 3, type);
            fill(image, new Color(0x336699));
            assertThat(scaler.toStreamPixels(image, new StreamSize(4, 3)))
                    .as("type %d", type)
                    .hasSize(12)
                    .containsOnly(0x336699);
        }
    }

    private static void fill(BufferedImage image, Color color) {
        Graphics2D g = image.createGraphics();
        try {
            g.setColor(color);
            g.fillRect(0, 0, image.getWidth(), image.getHeight());
        } finally {
            g.dispose();
        }
    }
}

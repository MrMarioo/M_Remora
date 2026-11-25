package pl.mtech.mremora.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.data.Offset.offset;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Random;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class JpegFrameEncoderTest {

    private static final int COLOUR = 0x3080C0;

    private static BufferedImage solid(int type) {
        BufferedImage image = new BufferedImage(64, 48, type);
        for (int y = 0; y < 48; y++) {
            for (int x = 0; x < 64; x++) {
                image.setRGB(x, y, 0xFF000000 | COLOUR);
            }
        }
        return image;
    }

    private static void assertColour(byte[] jpeg) throws IOException {
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(jpeg));
        assertThat(decoded.getWidth()).isEqualTo(64);
        assertThat(decoded.getHeight()).isEqualTo(48);
        int rgb = decoded.getRGB(32, 24);
        assertThat((rgb >> 16) & 0xFF).isCloseTo((COLOUR >> 16) & 0xFF, offset(8));
        assertThat((rgb >> 8) & 0xFF).isCloseTo((COLOUR >> 8) & 0xFF, offset(8));
        assertThat(rgb & 0xFF).isCloseTo(COLOUR & 0xFF, offset(8));
    }

    @Test
    void producesJpeg() {
        byte[] jpeg = new JpegFrameEncoder(JpegFrameEncoder.DEFAULT_QUALITY).encode(solid(BufferedImage.TYPE_INT_RGB));

        assertThat(jpeg[0]).isEqualTo((byte) 0xFF);
        assertThat(jpeg[1]).isEqualTo((byte) 0xD8);
        assertThat(jpeg[jpeg.length - 2]).isEqualTo((byte) 0xFF);
        assertThat(jpeg[jpeg.length - 1]).isEqualTo((byte) 0xD9);
    }

    @Test
    void roundTripsSizeAndColour() throws IOException {
        assertColour(new JpegFrameEncoder(JpegFrameEncoder.DEFAULT_QUALITY).encode(solid(BufferedImage.TYPE_INT_RGB)));
    }

    @Test
    void encodesImageWithAlpha() throws IOException {
        assertColour(new JpegFrameEncoder(JpegFrameEncoder.DEFAULT_QUALITY).encode(solid(BufferedImage.TYPE_INT_ARGB)));
    }

    @Test
    void lowerQualityGivesSmallerOutput() {
        BufferedImage noisy = new BufferedImage(64, 48, BufferedImage.TYPE_INT_RGB);
        Random random = new Random(42);
        for (int y = 0; y < 48; y++) {
            for (int x = 0; x < 64; x++) {
                noisy.setRGB(x, y, random.nextInt(0x1000000));
            }
        }

        int low = new JpegFrameEncoder(0.2f).encode(noisy).length;
        int high = new JpegFrameEncoder(0.9f).encode(noisy).length;

        assertThat(low).isLessThan(high);
    }

    @Test
    void rejectsQualityOutOfRange() {
        assertThatThrownBy(() -> new JpegFrameEncoder(-0.1f)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new JpegFrameEncoder(1.1f)).isInstanceOf(IllegalArgumentException.class);
    }
}

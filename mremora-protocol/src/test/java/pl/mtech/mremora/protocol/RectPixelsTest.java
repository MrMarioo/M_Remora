package pl.mtech.mremora.protocol;

import org.junit.jupiter.api.Test;

import java.util.Random;
import java.util.zip.Deflater;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RectPixelsTest {

    @Test
    void solidMatchesTheWireBytes() {
        assertThat(RectPixels.encodeSolid(0xAB112233)).containsExactly(0x00, 0x11, 0x22, 0x33);
    }

    @Test
    void solidRoundTripIsOpaque() {
        assertThat(RectPixels.decodeSolid(RectPixels.encodeSolid(0x112233))).isEqualTo(0xFF112233);
    }

    @Test
    void solidWithWrongLengthIsRejected() {
        assertThatThrownBy(() -> RectPixels.decodeSolid(new byte[3])).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RectPixels.decodeSolid(new byte[5])).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void solidWithNonZeroFirstByteIsRejected() {
        assertThatThrownBy(() -> RectPixels.decodeSolid(new byte[]{1, 2, 3, 4}))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void losslessRoundTripOfASubRect() {
        int[] image = new int[10 * 6];
        Random random = new Random(42);
        for (int i = 0; i < image.length; i++) {
            image[i] = random.nextInt();
        }
        byte[] data = RectPixels.encodeLossless(image, 10, 3, 2, 5, 3);
        int[] expected = new int[5 * 3];
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 5; col++) {
                expected[row * 5 + col] = image[(2 + row) * 10 + 3 + col] | 0xFF000000;
            }
        }
        assertThat(RectPixels.decodeLossless(data, 5, 3)).isEqualTo(expected);
    }

    @Test
    void losslessTooShortIsRejected() {
        byte[] data = RectPixels.encodeLossless(new int[16], 4, 0, 0, 4, 4);
        assertThatThrownBy(() -> RectPixels.decodeLossless(data, 4, 5)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void losslessTooLongIsRejected() {
        byte[] data = RectPixels.encodeLossless(new int[20], 4, 0, 0, 4, 5);
        assertThatThrownBy(() -> RectPixels.decodeLossless(data, 4, 4)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void losslessBombIsRejectedCheaply() {
        Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION);
        byte[] zeros = new byte[1 << 20];
        byte[] out = new byte[1 << 16];
        java.io.ByteArrayOutputStream bomb = new java.io.ByteArrayOutputStream();
        for (int i = 0; i < 50; i++) {
            deflater.setInput(zeros);
            while (!deflater.needsInput()) {
                bomb.write(out, 0, deflater.deflate(out));
            }
        }
        deflater.finish();
        while (!deflater.finished()) {
            bomb.write(out, 0, deflater.deflate(out));
        }
        deflater.end();
        byte[] data = bomb.toByteArray();
        assertThatThrownBy(() -> RectPixels.decodeLossless(data, 8, 8)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void losslessGarbageIsRejected() {
        assertThatThrownBy(() -> RectPixels.decodeLossless(new byte[]{1, 2, 3}, 4, 4))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void losslessSizeOverflowIsRejectedBeforeAllocating() {
        // 46341^2 overflows int; 30000x30000x3 overflows only after the *3.
        assertThatThrownBy(() -> RectPixels.decodeLossless(new byte[]{1}, 46341, 46341))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RectPixels.decodeLossless(new byte[]{1}, 30000, 30000))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RectPixels.encodeLossless(new int[1], 1, 0, 0, 30000, 30000))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void negativeLosslessSizeIsRejected() {
        assertThatThrownBy(() -> RectPixels.decodeLossless(new byte[]{1}, -1, -1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RectPixels.decodeLossless(new byte[]{1}, -1, 4))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RectPixels.encodeLossless(new int[1], 1, 0, 0, -1, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

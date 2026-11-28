package pl.mtech.mremora.protocol.udp;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.ByteBuffer;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class TileFragmentTest {

    private static TileFragment valid() {
        return new TileFragment(1, 64, 32, 7, 9, 0, 0, 2, 1, 3, 3, 1, new byte[] {(byte) 0xAB});
    }

    private static byte[] wire(TileFragment f) {
        return f.encode();
    }

    /** Re-encodes with one raw byte range overwritten, to build payloads the record would refuse to construct. */
    private static byte[] patched(int offset, long value, int length) {
        byte[] b = valid().encode();
        for (int i = 0; i < length; i++) {
            b[offset + length - 1 - i] = (byte) (value >>> (8 * i));
        }
        return b;
    }

    @Test
    void wireFormat() {
        assertThat(valid().encode()).containsExactly(
                0x00, 0x01, 0x00, 0x40, 0x00, 0x20,
                0, 0, 0, 0, 0, 0, 0, 7,
                0, 0, 0, 9,
                0, 0, 0, 0, 0, 2, 0, 1,
                3,
                0, 0, 0, 3,
                0, 0, 0, 1,
                0xAB);
        assertThat(TileFragment.HEADER).isEqualTo(35);
    }

    @Test
    void roundTrip() {
        byte[] data = new byte[TileFragment.MAX_DATA];
        Arrays.fill(data, (byte) 0x5A);
        TileFragment f = new TileFragment(65535, 65535, 65535, Long.MAX_VALUE, 0xFFFFFFFFL, 65000, 65000, 535, 535, 2,
                TileFragment.MAX_RECT_BYTES, TileFragment.MAX_RECT_BYTES - TileFragment.MAX_DATA, data);

        byte[] encoded = f.encode();

        assertThat(encoded).hasSize(UdpPacketCodec.MAX_PAYLOAD);
        assertThat(TileFragment.decode(encoded)).contains(f);
        assertThat(TileFragment.decode(valid().encode())).contains(valid());
    }

    @Test
    void tileFragmentRejectsOutsideStream() {
        assertThat(TileFragment.decode(patched(18, 63, 2))).isEmpty(); // x=63, w=2 > 64
        assertThat(TileFragment.decode(patched(20, 32, 2))).isEmpty(); // y=32, h=1 > 32
        assertThat(TileFragment.decode(patched(22, 65, 2))).isEmpty(); // w=65 > 64
        assertThat(TileFragment.decode(patched(2, 0, 2))).isEmpty(); // stream width 0
        assertThat(TileFragment.decode(patched(4, 0, 2))).isEmpty(); // stream height 0
    }

    @Test
    void tileFragmentRejectsOffsetPastTotal() {
        assertThat(TileFragment.decode(patched(31, 3, 4))).isEmpty(); // offset 3 + 1 > 3
    }

    @Test
    void tileFragmentRejectsHugeTotal() {
        byte[] b = patched(27, TileFragment.MAX_RECT_BYTES + 1L, 4);
        assertThat(TileFragment.decode(b)).isEmpty();
        assertThat(TileFragment.decode(patched(27, 0xFFFFFFFFL, 4))).isEmpty();
        assertThat(TileFragment.decode(patched(27, TileFragment.MAX_RECT_BYTES, 4))).isPresent();
    }

    @Test
    void tileFragmentRejectsZeroSize() {
        assertThat(TileFragment.decode(patched(22, 0, 2))).isEmpty();
        assertThat(TileFragment.decode(patched(24, 0, 2))).isEmpty();
    }

    @Test
    void tileFragmentRejectsUnknownEncoding() {
        assertThat(TileFragment.decode(patched(26, 0, 1))).isEmpty();
        assertThat(TileFragment.decode(patched(26, 4, 1))).isEmpty();
        assertThat(TileFragment.decode(patched(26, 255, 1))).isEmpty();
        assertThat(TileFragment.decode(patched(26, 1, 1))).isPresent();
        assertThat(TileFragment.decode(patched(26, 2, 1))).isPresent();
    }

    @Test
    void tileFragmentRejectsEmptyData() {
        assertThat(TileFragment.decode(Arrays.copyOf(valid().encode(), TileFragment.HEADER))).isEmpty();
    }

    @Test
    void tileFragmentRejectsRectIdZero() {
        assertThat(TileFragment.decode(patched(14, 0, 4))).isEmpty();
    }

    @Test
    void tileFragmentRejectsTruncated() {
        byte[] full = valid().encode();
        for (int n = 0; n < TileFragment.HEADER; n++) {
            assertThat(TileFragment.decode(Arrays.copyOf(full, n))).isEmpty();
        }
    }

    @Test
    void tileFragmentRejectsOversizedData() {
        byte[] b = new byte[TileFragment.HEADER + TileFragment.MAX_DATA + 1];
        ByteBuffer.wrap(b).put(patched(0, 0, 0), 0, TileFragment.HEADER);
        // header of valid() says total 3, so the data cannot fit anyway
        assertThat(TileFragment.decode(b)).isEmpty();
    }

    @Test
    void constructorRejectsInvalidAndWireIsUnchanged() {
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> new TileFragment(1, 64, 32, 7, 0, 0, 0, 2, 1, 3, 3, 1, new byte[] {1}))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(wire(valid())).isEqualTo(valid().encode());
    }
}

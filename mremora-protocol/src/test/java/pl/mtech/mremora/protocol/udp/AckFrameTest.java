package pl.mtech.mremora.protocol.udp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import pl.mtech.mremora.protocol.udp.RangeSet.Range;

class AckFrameTest {

    private static AckFrame sample() {
        return new AckFrame(3, List.of(new Range(10, 12), new Range(5, 5)), List.of(new Range(1, 2)), List.of(7L));
    }

    /** Hand-built wire: delay, packet ranges, done ranges, dropped. */
    private static byte[] raw(int delay, long[][] packets, long[][] done, long[] dropped) {
        ByteBuffer b = ByteBuffer.allocate(2048);
        b.putShort((short) delay).put((byte) packets.length);
        for (long[] r : packets) {
            b.putLong(r[0]).putLong(r[1]);
        }
        b.put((byte) done.length);
        for (long[] r : done) {
            b.putInt((int) r[0]).putInt((int) r[1]);
        }
        b.put((byte) dropped.length);
        for (long d : dropped) {
            b.putInt((int) d);
        }
        return Arrays.copyOf(b.array(), b.position());
    }

    @Test
    void wireFormat() {
        assertThat(sample().encode()).containsExactly(
                0, 3,
                2,
                0, 0, 0, 0, 0, 0, 0, 10, 0, 0, 0, 0, 0, 0, 0, 12,
                0, 0, 0, 0, 0, 0, 0, 5, 0, 0, 0, 0, 0, 0, 0, 5,
                1,
                0, 0, 0, 1, 0, 0, 0, 2,
                1,
                0, 0, 0, 7);
    }

    @Test
    void roundTrip() {
        assertThat(AckFrame.decode(sample().encode())).contains(sample());
        AckFrame empty = new AckFrame(0, List.of(), List.of(), List.of());
        assertThat(empty.encode()).hasSize(5);
        assertThat(AckFrame.decode(empty.encode())).contains(empty);
        AckFrame extreme = new AckFrame(65535, List.of(new Range(0, Long.MAX_VALUE)),
                List.of(new Range(0, 0xFFFFFFFFL)), List.of(0xFFFFFFFFL));
        assertThat(AckFrame.decode(extreme.encode())).contains(extreme);
    }

    @Test
    void maxSizeFits() {
        List<Range> packets = new ArrayList<>();
        List<Range> done = new ArrayList<>();
        List<Long> dropped = new ArrayList<>();
        for (int i = 0; i < 32; i++) {
            packets.add(new Range(1000 - 10 * i, 1000 - 10 * i + 5));
            done.add(new Range(500 - 10 * i, 500 - 10 * i + 5));
            dropped.add((long) i);
        }
        AckFrame f = new AckFrame(25, packets, done, dropped);

        byte[] encoded = f.encode();

        assertThat(encoded).hasSize(901).hasSizeLessThanOrEqualTo(UdpPacketCodec.MAX_PAYLOAD);
        assertThat(AckFrame.decode(encoded)).contains(f);
    }

    @Test
    void ackFrameRejectsUnordered() {
        assertThat(AckFrame.decode(raw(0, new long[][] {{5, 5}, {10, 12}}, new long[0][], new long[0]))).isEmpty();
        assertThat(AckFrame.decode(raw(0, new long[0][], new long[][] {{1, 2}, {5, 6}}, new long[0]))).isEmpty();
    }

    @Test
    void ackFrameRejectsOverlapping() {
        assertThat(AckFrame.decode(raw(0, new long[][] {{10, 12}, {5, 10}}, new long[0][], new long[0]))).isEmpty();
        assertThat(AckFrame.decode(raw(0, new long[0][], new long[][] {{5, 8}, {1, 5}}, new long[0]))).isEmpty();
        // strictly disjoint neighbours are fine
        assertThat(AckFrame.decode(raw(0, new long[][] {{10, 12}, {5, 9}}, new long[0][], new long[0]))).isPresent();
    }

    @Test
    void ackFrameRejectsFromAfterTo() {
        assertThat(AckFrame.decode(raw(0, new long[][] {{12, 10}}, new long[0][], new long[0]))).isEmpty();
        assertThat(AckFrame.decode(raw(0, new long[0][], new long[][] {{2, 1}}, new long[0]))).isEmpty();
        assertThat(AckFrame.decode(raw(0, new long[][] {{-1, 4}}, new long[0][], new long[0]))).isEmpty();
    }

    @Test
    void ackFrameRejectsTooManyRanges() {
        long[][] many = new long[33][];
        for (int i = 0; i < 33; i++) {
            many[i] = new long[] {1000 - 10 * i, 1000 - 10 * i + 5};
        }
        assertThat(AckFrame.decode(raw(0, many, new long[0][], new long[0]))).isEmpty();
        assertThat(AckFrame.decode(raw(0, new long[0][], many, new long[0]))).isEmpty();
        assertThat(AckFrame.decode(raw(0, new long[0][], new long[0][], new long[33]))).isEmpty();
        assertThatThrownBy(() -> new AckFrame(0, Arrays.stream(many).map(r -> new Range(r[0], r[1])).toList(),
                List.of(), List.of())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void ackFrameRejectsTruncated() {
        byte[] full = sample().encode();
        for (int n = 0; n < full.length; n++) {
            assertThat(AckFrame.decode(Arrays.copyOf(full, n))).as("length %d", n).isEmpty();
        }
    }

    @Test
    void ackFrameRejectsTrailingBytes() {
        byte[] full = sample().encode();
        assertThat(AckFrame.decode(Arrays.copyOf(full, full.length + 1))).isEmpty();
    }
}

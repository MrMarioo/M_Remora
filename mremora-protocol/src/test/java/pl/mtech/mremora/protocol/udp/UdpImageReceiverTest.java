package pl.mtech.mremora.protocol.udp;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import pl.mtech.mremora.protocol.udp.RangeSet.Range;
import pl.mtech.mremora.protocol.udp.UdpPacket.Direction;
import pl.mtech.mremora.protocol.udp.UdpPacket.Type;

class UdpImageReceiverTest {

    private static final int SESSION = 0x0EC0_0001;
    private static final long MS = 1_000_000L;
    private static final long T0 = Long.MAX_VALUE - 500 * MS;

    private final byte[] key = key();
    private final UdpPeer agentPeer = new UdpPeer(Direction.AGENT, SESSION, key);
    private final UdpPeer viewerPeer = new UdpPeer(Direction.VIEWER, SESSION, key);
    private final UdpImageReceiver receiver = new UdpImageReceiver(viewerPeer);

    private static byte[] key() {
        byte[] k = new byte[32];
        Arrays.fill(k, (byte) 3);
        return k;
    }

    /** A sealed, opened TILE packet carrying a one-fragment rect {@code rectId}. */
    private UdpPacket tile(long rectId) {
        TileFragment f = new TileFragment(0, 640, 480, rectId, rectId, 0, 0, 64, 64, TileFragment.LOSSLESS,
                10, 0, new byte[10]);
        return viewerPeer.open(ByteBuffer.wrap(agentPeer.seal(Type.TILE, f.encode()))).orElseThrow();
    }

    /** First fragment of a two-fragment rect (never completes). */
    private UdpPacket partialTile(long rectId) {
        TileFragment f = new TileFragment(0, 640, 480, rectId, rectId, 0, 0, 64, 64, TileFragment.LOSSLESS,
                20, 0, new byte[10]);
        return viewerPeer.open(ByteBuffer.wrap(agentPeer.seal(Type.TILE, f.encode()))).orElseThrow();
    }

    private AckFrame ack(byte[] datagram) {
        UdpPacket p = agentPeer.open(ByteBuffer.wrap(datagram)).orElseThrow();
        assertThat(p.type()).isEqualTo(Type.ACK);
        return AckFrame.decode(p.payload()).orElseThrow();
    }

    @Test
    void receiverAcksAfterTwoPackets() {
        UdpPacket a = tile(1);
        UdpPacket b = tile(2);
        Optional<CompleteRect> r = receiver.onPacket(a, T0);
        assertThat(r).isPresent();
        assertThat(receiver.poll(T0)).isEmpty();

        receiver.onPacket(b, T0 + MS);
        assertThat(receiver.nextDeadlineNanos(T0 + MS)).isEqualTo(T0 + MS);
        List<byte[]> out = receiver.poll(T0 + MS);
        assertThat(out).hasSize(1);
        assertThat(ack(out.getFirst()).packets()).containsExactly(new Range(a.number(), b.number()));
        assertThat(receiver.poll(T0 + 2 * MS)).isEmpty();
        assertThat(receiver.epoch()).isZero();
    }

    @Test
    void receiverAcksWithin25ms() {
        UdpPacket a = tile(1);
        receiver.onPacket(a, T0);
        assertThat(receiver.poll(T0 + 24 * MS)).isEmpty();
        assertThat(receiver.nextDeadlineNanos(T0 + 24 * MS)).isEqualTo(T0 + 25 * MS);

        List<byte[]> out = receiver.poll(T0 + 25 * MS);
        assertThat(out).hasSize(1);
        AckFrame f = ack(out.getFirst());
        assertThat(f.packets()).containsExactly(new Range(a.number(), a.number()));
        assertThat(f.ackDelayMs()).isEqualTo(25);
    }

    @Test
    void finishingARectMakesAnAckDueWithin25ms() {
        receiver.onPacket(tile(1), T0);
        receiver.onPacket(tile(2), T0);
        assertThat(receiver.poll(T0)).hasSize(1); // both packets acknowledged

        receiver.applied(1);
        assertThat(receiver.nextDeadlineNanos(T0 + MS)).isEqualTo(T0 + 26 * MS);
        assertThat(receiver.poll(T0 + 25 * MS)).isEmpty();
        List<byte[]> out = receiver.poll(T0 + 26 * MS);
        assertThat(out).hasSize(1);
        assertThat(ack(out.getFirst()).doneRects()).containsExactly(new Range(1, 1));
    }

    @Test
    void probeIsAcknowledgedButNotAnswered() {
        UdpPacket probe = viewerPeer.open(ByteBuffer.wrap(agentPeer.probe(T0))).orElseThrow();
        assertThat(receiver.onPacket(probe, T0)).isEmpty();
        List<byte[]> out = receiver.poll(T0 + 25 * MS);
        assertThat(out).hasSize(1);
        assertThat(ack(out.getFirst()).packets()).containsExactly(new Range(probe.number(), probe.number()));
    }

    @Test
    void receiverRepeatsDroppedThreeTimes() {
        receiver.dropped(7);
        assertThat(receiver.nextDeadlineNanos(T0)).isEqualTo(T0 + 25 * MS);
        List<byte[]> first = receiver.poll(T0 + 25 * MS);
        assertThat(first).hasSize(1);
        assertThat(ack(first.getFirst()).droppedRects()).containsExactly(7L);
        long now = T0 + 25 * MS;
        for (int i = 0; i < 3; i++) {
            receiver.onPacket(tile(100 + 2L * i), now); // two packets make the next ACK due at once
            receiver.onPacket(tile(101 + 2L * i), now);
            List<byte[]> out = receiver.poll(now);
            assertThat(out).hasSize(1);
            if (i < 2) {
                assertThat(ack(out.getFirst()).droppedRects()).containsExactly(7L);
            } else {
                assertThat(ack(out.getFirst()).droppedRects()).isEmpty();
            }
            now += MS;
        }
    }

    @Test
    void finishTimerIsStampedByTheNextCall() {
        receiver.applied(3);
        List<byte[]> none = receiver.poll(T0); // stamps the finish at T0
        assertThat(none).isEmpty();
        assertThat(receiver.poll(T0 + 25 * MS)).hasSize(1);
        assertThat(receiver.poll(T0 + 60 * MS)).isEmpty();
    }

    @Test
    void idleReceiverHasNoUrgentDeadline() {
        assertThat(receiver.nextDeadlineNanos(T0) - T0).isGreaterThanOrEqualTo(100 * MS);
    }

    @Test
    void expiredRectIsReportedDropped() {
        receiver.onPacket(partialTile(5), T0);
        assertThat(receiver.heldBytes()).isPositive();
        receiver.poll(T0 + 25 * MS); // ACK of the packet
        assertThat(receiver.poll(T0 + RectAssembler.EXPIRE_NANOS)).isEmpty(); // expires; ACK due within 25 ms
        assertThat(receiver.heldBytes()).isZero();
        List<byte[]> out = receiver.poll(T0 + RectAssembler.EXPIRE_NANOS + 25 * MS);
        assertThat(out).hasSize(1);
        assertThat(ack(out.getFirst()).droppedRects()).containsExactly(5L);
    }
}

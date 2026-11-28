package pl.mtech.mremora.protocol.udp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import pl.mtech.mremora.protocol.udp.UdpImageSender.OutgoingRect;
import pl.mtech.mremora.protocol.udp.UdpPacket.Direction;
import pl.mtech.mremora.protocol.udp.UdpPacket.Type;

class UdpImageSenderTest {

    private static final int SESSION = 0x5E55_1011;
    private static final long MS = 1_000_000L;
    /** Start close to the wrap of {@code long} so every time comparison must be overflow-safe. */
    private static final long T0 = Long.MAX_VALUE - 1_000 * MS;

    private final byte[] key = key();
    private final UdpPeer agentPeer = new UdpPeer(Direction.AGENT, SESSION, key);
    private final UdpPeer viewerPeer = new UdpPeer(Direction.VIEWER, SESSION, key);

    private static byte[] key() {
        byte[] k = new byte[32];
        Arrays.fill(k, (byte) 7);
        return k;
    }

    private static OutgoingRect rect(int x, int y, int w, int h, int bytes) {
        return new OutgoingRect(x, y, w, h, TileFragment.LOSSLESS, new byte[bytes]);
    }

    private List<UdpPacket> openAll(List<byte[]> datagrams) {
        List<UdpPacket> out = new ArrayList<>();
        for (byte[] d : datagrams) {
            out.add(viewerPeer.open(ByteBuffer.wrap(d)).orElseThrow());
        }
        return out;
    }

    @Test
    void senderSealsAtSendTime() {
        UdpImageSender sender = new UdpImageSender(agentPeer, T0);
        sender.newEpoch(0, 1920, 1080);
        sender.submit(0, 1, List.of(rect(0, 0, 256, 64, 200_000)), T0);

        List<UdpPacket> first = openAll(sender.poll(T0));
        assertThat(first).isNotEmpty().allMatch(p -> p.type() == Type.TILE);
        assertThat(sender.queuedPackets()).isPositive(); // the pacer holds the rest

        // No ACK at all: the PTO (300 ms before any RTT sample) fires while fragments are still queued.
        long pto = T0 + 300 * MS;
        List<UdpPacket> second = openAll(sender.poll(pto));
        assertThat(second).anyMatch(p -> p.type() == Type.PROBE);
        assertThat(second).anyMatch(p -> p.type() == Type.TILE);

        List<UdpPacket> all = new ArrayList<>(first);
        all.addAll(second);
        for (int i = 1; i < all.size(); i++) {
            assertThat(all.get(i).number()).isGreaterThan(all.get(i - 1).number()); // numbering = send order
        }
        long probe = second.stream().filter(p -> p.type() == Type.PROBE).findFirst().orElseThrow().number();
        assertThat(all).anyMatch(p -> p.type() == Type.TILE && p.number() < probe);
        assertThat(all).anyMatch(p -> p.type() == Type.TILE && p.number() > probe);
    }

    @Test
    void senderIgnoresStaleEpochSubmit() {
        UdpImageSender sender = new UdpImageSender(agentPeer, T0);
        sender.newEpoch(1, 640, 480);
        sender.submit(0, 1, List.of(rect(0, 0, 64, 64, 500)), T0);

        assertThat(sender.poll(T0)).isEmpty();
        assertThat(sender.outstandingBytes()).isZero();
        assertThat(sender.epoch()).isEqualTo(1);
    }

    @Test
    void newEpochDiscardsQueuedFragmentsOfTheOldOne() {
        UdpImageSender sender = new UdpImageSender(agentPeer, T0);
        sender.newEpoch(0, 1920, 1080);
        sender.submit(0, 1, List.of(rect(0, 0, 256, 64, 200_000)), T0);
        sender.poll(T0);
        assertThat(sender.queuedPackets()).isPositive();

        sender.newEpoch(1, 800, 600);
        assertThat(sender.queuedPackets()).isZero();
        assertThat(sender.outstandingBytes()).isZero();
        assertThat(sender.admitsCapture()).isTrue();
    }

    @Test
    void admitsCaptureWhenIdleEvenIfLarge() {
        UdpImageSender sender = new UdpImageSender(agentPeer, T0);
        UdpImageReceiver receiver = new UdpImageReceiver(viewerPeer);
        sender.newEpoch(0, 1920, 1080);
        assertThat(sender.admitsCapture()).isTrue();

        // A first frame of 600 KB, far above the 64 KiB budget: admitted because nothing is outstanding.
        List<OutgoingRect> frame = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            frame.add(rect(i * 64, 0, 64, 64, 30_000));
        }
        sender.submit(0, 1, frame, T0);
        assertThat(sender.outstandingBytes()).isEqualTo(600_000);
        assertThat(sender.admitsCapture()).isFalse();

        // Loop the two sides back to back (1 ms per step) until every rect is confirmed.
        long now = T0;
        boolean sawBlockedWithAcks = false;
        for (int step = 0; step < 10_000 && sender.outstandingBytes() > 0; step++) {
            for (byte[] d : sender.poll(now)) {
                UdpPacket p = viewerPeer.open(ByteBuffer.wrap(d)).orElseThrow();
                Optional<CompleteRect> done = receiver.onPacket(p, now);
                done.ifPresent(r -> receiver.applied(r.rectId()));
            }
            for (byte[] d : receiver.poll(now)) {
                sender.onPacket(agentPeer.open(ByteBuffer.wrap(d)).orElseThrow(), now);
                if (sender.outstandingBytes() > 64 * 1024 && sender.outstandingBytes() < 600_000) {
                    sawBlockedWithAcks |= !sender.admitsCapture();
                }
            }
            now += MS;
        }
        assertThat(sawBlockedWithAcks).as("still gated after the first confirmations").isTrue();
        assertThat(sender.outstandingBytes()).isZero();
        assertThat(sender.admitsCapture()).isTrue();
        assertThat(sender.takeForced().tiles().isEmpty()).isTrue();
    }

    @Test
    void idleSinglePacketRectDoesNotGrowRate() {
        UdpImageSender sender = new UdpImageSender(agentPeer, T0);
        UdpImageReceiver receiver = new UdpImageReceiver(viewerPeer);
        sender.newEpoch(0, 640, 480);
        long rate = sender.rateBitsPerSecond();

        long now = T0;
        for (int i = 1; i <= 20; i++) {
            sender.submit(0, i, List.of(rect(0, 0, 64, 64, 500)), now);
            for (int step = 0; step < 50; step++) {
                for (byte[] d : sender.poll(now)) {
                    UdpPacket p = viewerPeer.open(ByteBuffer.wrap(d)).orElseThrow();
                    receiver.onPacket(p, now).ifPresent(r -> receiver.applied(r.rectId()));
                }
                for (byte[] d : receiver.poll(now)) {
                    sender.onPacket(agentPeer.open(ByteBuffer.wrap(d)).orElseThrow(), now);
                }
                now += MS;
            }
        }
        assertThat(sender.outstandingBytes()).isZero();
        assertThat(sender.smoothedRttNanos()).isPresent();
        assertThat(sender.rateBitsPerSecond()).isEqualTo(rate); // the pacer was never the limit
    }

    /** Delivers every datagram of the sender to the receiver; returns the opened packets. */
    private List<UdpPacket> deliver(UdpImageSender sender, UdpImageReceiver receiver, long now, List<CompleteRect> got) {
        List<UdpPacket> out = new ArrayList<>();
        for (byte[] d : sender.poll(now)) {
            UdpPacket p = viewerPeer.open(ByteBuffer.wrap(d)).orElseThrow();
            out.add(p);
            receiver.onPacket(p, now).ifPresent(got::add);
        }
        return out;
    }

    private void ackBack(UdpImageSender sender, UdpImageReceiver receiver, long now) {
        for (byte[] d : receiver.poll(now)) {
            sender.onPacket(agentPeer.open(ByteBuffer.wrap(d)).orElseThrow(), now);
        }
    }

    @Test
    void gateAdmitsAtMostTwoUnresolvedCaptures() {
        UdpImageSender sender = new UdpImageSender(agentPeer, T0);
        UdpImageReceiver receiver = new UdpImageReceiver(viewerPeer);
        sender.newEpoch(0, 640, 480);
        List<CompleteRect> got = new ArrayList<>();
        sender.submit(0, 1, List.of(rect(0, 0, 64, 64, 500)), T0);
        assertThat(sender.admitsCapture()).isTrue();
        sender.submit(0, 2, List.of(rect(64, 0, 64, 64, 500)), T0);
        deliver(sender, receiver, T0, got);
        ackBack(sender, receiver, T0 + 25 * MS);
        assertThat(sender.networkBytes()).isZero(); // the network side is open...
        assertThat(sender.admitsCapture()).isFalse(); // ...but the viewer has not applied either capture

        receiver.applied(got.getFirst().rectId());
        receiver.nextDeadlineNanos(T0 + 30 * MS);
        ackBack(sender, receiver, T0 + 55 * MS);
        assertThat(sender.admitsCapture()).isTrue();
    }

    @Test
    void resolveProbeElicitsAckWhileTheViewerApplies() {
        UdpImageSender sender = new UdpImageSender(agentPeer, T0);
        UdpImageReceiver receiver = new UdpImageReceiver(viewerPeer);
        sender.newEpoch(0, 640, 480);
        List<CompleteRect> got = new ArrayList<>();
        sender.submit(0, 1, List.of(rect(0, 0, 64, 64, 500)), T0);
        deliver(sender, receiver, T0, got);
        ackBack(sender, receiver, T0 + 25 * MS); // packet acknowledged, rect not applied yet
        long next = T0 + UdpImageSender.RESOLVE_PROBE_MIN_NANOS; // 200 ms after the last send (PTO is shorter here)
        assertThat(sender.nextDeadlineNanos(next - 5 * MS)).isEqualTo(next);

        // the viewer applies it, but the ACK reporting that is lost
        receiver.applied(got.getFirst().rectId());
        receiver.nextDeadlineNanos(T0 + 30 * MS);
        assertThat(receiver.poll(T0 + 55 * MS)).hasSize(1); // dropped on the way
        assertThat(sender.admitsCapture()).isTrue(); // one unresolved capture still admits
        assertThat(sender.outstandingBytes()).isPositive();

        assertThat(sender.poll(next - MS)).isEmpty();
        List<UdpPacket> probe = deliver(sender, receiver, next, got);
        assertThat(probe).extracting(UdpPacket::type).containsExactly(Type.PROBE);
        ackBack(sender, receiver, next + 25 * MS); // the probe's ACK repeats the finished rect
        assertThat(sender.outstandingBytes()).isZero();
        assertThat(sender.confirmTimeouts()).isZero();
    }

    @Test
    void timedOutRectLeavesTheLiveSetWhileOthersAreOutstanding() {
        UdpImageSender sender = new UdpImageSender(agentPeer, T0);
        UdpImageReceiver receiver = new UdpImageReceiver(viewerPeer);
        sender.newEpoch(0, 640, 480);
        List<CompleteRect> got = new ArrayList<>();
        sender.submit(0, 1, List.of(rect(0, 0, 64, 64, 500)), T0);
        deliver(sender, receiver, T0, got);
        ackBack(sender, receiver, T0 + 25 * MS); // acked, never applied
        long t = T0 + 1_900 * MS;
        sender.submit(0, 2, List.of(rect(64, 0, 64, 64, 500)), t);
        assertThat(sender.poll(t)).isNotEmpty(); // sent, lost in the network: stays outstanding
        assertThat(sender.trackedLiveRects()).isEqualTo(2);

        sender.poll(T0 + 25 * MS + TileDelivery.CONFIRM_TIMEOUT_NANOS);
        assertThat(sender.confirmTimeouts()).isEqualTo(1);
        assertThat(sender.outstandingBytes()).isPositive();
        assertThat(sender.trackedLiveRects()).isEqualTo(1).isEqualTo(sender.deliveryLiveRects());
    }

    @Test
    void liveSetStaysBoundedUnderContinuousChangesWithTimeouts() {
        UdpImageSender sender = new UdpImageSender(agentPeer, T0);
        UdpImageReceiver receiver = new UdpImageReceiver(viewerPeer);
        sender.newEpoch(0, 640, 480);
        long now = T0;
        long capture = 0;
        int tile = 0;
        int maxLive = 0;
        boolean timeoutWhileOutstanding = false;
        for (int step = 0; step < 60_000; step++) { // 60 s in 1 ms steps; the viewer never applies anything
            if (step % 33 == 0 && sender.admitsCapture()) {
                tile = (tile + 1) % 70;
                sender.submit(0, ++capture, List.of(rect((tile % 10) * 64, (tile / 10) * 64, 64, 64, 500)), now);
            }
            long timeoutsBefore = sender.confirmTimeouts();
            List<byte[]> out = sender.poll(now);
            if (sender.confirmTimeouts() > timeoutsBefore && sender.outstandingBytes() > 0) {
                timeoutWhileOutstanding = true;
            }
            for (byte[] d : out) {
                UdpPacket p = viewerPeer.open(ByteBuffer.wrap(d)).orElseThrow();
                if (p.type() == Type.TILE && p.number() % 3 == 0) {
                    continue; // lost on the way
                }
                receiver.onPacket(p, now);
            }
            ackBack(sender, receiver, now);
            assertThat(sender.trackedLiveRects()).isEqualTo(sender.deliveryLiveRects());
            maxLive = Math.max(maxLive, sender.trackedLiveRects());
            now += MS;
        }
        assertThat(sender.confirmTimeouts()).isPositive();
        assertThat(timeoutWhileOutstanding).isTrue();
        assertThat(maxLive).isLessThanOrEqualTo(UdpImageSender.MAX_UNRESOLVED_CAPTURES);
    }

    @Test
    void invalidRectIsRejectedBeforeRegistering() {
        UdpImageSender sender = new UdpImageSender(agentPeer, T0);
        sender.newEpoch(0, 640, 480);
        assertThatThrownBy(
                        () -> sender.submit(0, 1, List.of(rect(0, 0, 64, 64, 0)), T0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () -> sender.submit(0, 1, List.of(rect(600, 0, 64, 64, 10)), T0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () -> sender.submit(0, -1, List.of(rect(0, 0, 64, 64, 10)), T0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> sender.newEpoch(1, 70_000, 480))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(sender.outstandingBytes()).isZero();
    }

    @Test
    void captureSeqMustGrowAcrossTheSession() {
        UdpImageSender sender = new UdpImageSender(agentPeer, T0);
        sender.newEpoch(0, 640, 480);
        assertThat(sender.submit(0, 5, List.of(rect(0, 0, 64, 64, 10)), T0)).isTrue();

        // A same-seq resend would be skipped by the viewer's tile versions and leave the tile stale.
        assertThatThrownBy(() -> sender.submit(0, 5, List.of(rect(64, 0, 64, 64, 10)), T0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> sender.submit(0, 4, List.of(rect(64, 0, 64, 64, 10)), T0))
                .isInstanceOf(IllegalArgumentException.class);
        sender.newEpoch(1, 320, 240); // a new epoch does not restart the sequence
        assertThatThrownBy(() -> sender.submit(1, 5, List.of(rect(0, 0, 64, 64, 10)), T0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(sender.rectsSubmitted()).isEqualTo(1);
        assertThat(sender.submit(1, 6, List.of(rect(0, 0, 64, 64, 10)), T0)).isTrue();
    }

    @Test
    void staleEpochSubmitIsReportedAsIgnored() {
        UdpImageSender sender = new UdpImageSender(agentPeer, T0);
        assertThat(sender.submit(0, 1, List.of(rect(0, 0, 64, 64, 10)), T0)).isFalse(); // no epoch yet
        sender.newEpoch(1, 640, 480);

        assertThat(sender.submit(0, 2, List.of(rect(0, 0, 64, 64, 10)), T0)).isFalse();
        assertThat(sender.submit(1, 3, List.of(rect(0, 0, 64, 64, 10)), T0)).isTrue();
    }

    @Test
    void countersForTheStats() {
        UdpImageSender sender = new UdpImageSender(agentPeer, T0);
        sender.newEpoch(0, 640, 480);
        sender.submit(0, 1, List.of(rect(0, 0, 256, 64, 3000), rect(0, 64, 64, 64, 10)), T0);

        List<byte[]> sent = sender.poll(T0);

        assertThat(sender.rectsSubmitted()).isEqualTo(2);
        assertThat(sender.fragmentsQueued()).isEqualTo(4); // 3000 B -> 3 fragments, 10 B -> 1
        assertThat(sender.maxFragmentsPerRect()).isEqualTo(3);
        assertThat(sender.tilePacketsSent()).isEqualTo(sent.size()).isEqualTo(4);
        assertThat(sender.lostPackets()).isZero();
        assertThat(sender.tilesForced()).isZero();
    }
}

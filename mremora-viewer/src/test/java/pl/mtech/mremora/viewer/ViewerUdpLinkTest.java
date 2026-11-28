package pl.mtech.mremora.viewer;

import static org.assertj.core.api.Assertions.assertThat;

import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import pl.mtech.mremora.protocol.UdpSetup;
import pl.mtech.mremora.protocol.netty.LinkImpairment;
import pl.mtech.mremora.protocol.udp.AckFrame;
import pl.mtech.mremora.protocol.udp.CompleteRect;
import pl.mtech.mremora.protocol.udp.ProbePayload;
import pl.mtech.mremora.protocol.udp.RangeSet.Range;
import pl.mtech.mremora.protocol.udp.RectFragmenter;
import pl.mtech.mremora.protocol.udp.TileFragment;
import pl.mtech.mremora.protocol.udp.UdpPacket;
import pl.mtech.mremora.protocol.udp.UdpPacket.Direction;
import pl.mtech.mremora.protocol.udp.UdpPacket.Type;
import pl.mtech.mremora.protocol.udp.UdpPacketCodec;

/** {@link ViewerUdpLink} against a raw {@link DatagramSocket} playing the agent. */
class ViewerUdpLinkTest {

    /** Generous upper bound for anything that should happen "now"; only reached when a test fails. */
    private static final Duration WAIT = Duration.ofSeconds(5);
    private static final int SESSION_ID = 0x1234_5678;

    private final byte[] key = key(7);
    private final EventLoopGroup group = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
    private final List<ViewerUdpLink> links = new ArrayList<>();
    private FakeUdpAgent agent;

    @AfterEach
    void tearDown() {
        links.forEach(ViewerUdpLink::close);
        if (agent != null) {
            agent.close();
        }
        group.shutdownGracefully(0, 5, TimeUnit.SECONDS).syncUninterruptibly();
    }

    private static byte[] key(int seed) {
        byte[] k = new byte[UdpSetup.KEY_LENGTH];
        Arrays.fill(k, (byte) seed);
        return k;
    }

    private ViewerUdpLink open(LinkImpairment impairment) throws IOException {
        return open(impairment, rect -> {});
    }

    private ViewerUdpLink open(LinkImpairment impairment, Consumer<CompleteRect> rects) throws IOException {
        return open(impairment, rects, () -> {});
    }

    private ViewerUdpLink open(LinkImpairment impairment, Consumer<CompleteRect> rects, Runnable onFailure)
            throws IOException {
        agent = new FakeUdpAgent(key);
        UdpSetup setup = new UdpSetup(agent.port(), SESSION_ID, key);
        ViewerUdpLink link = ViewerUdpLink.open(group, agent.address(), setup, impairment, rects, onFailure);
        links.add(link);
        return link;
    }

    /** A rectangle of {@code length} bytes (3 fragments for 3000 B). */
    private static CompleteRect rect(long rectId, int length) {
        byte[] data = new byte[length];
        for (int i = 0; i < length; i++) {
            data[i] = (byte) (i * 31 + rectId);
        }
        return new CompleteRect(0, 640, 480, 9, rectId, 64, 128, 256, 64, TileFragment.LOSSLESS, data);
    }

    private static AckFrame ackOf(UdpPacket p) {
        return AckFrame.decode(p.payload()).orElseThrow();
    }

    @Test
    void persistentPollFailureEndsTheSession() throws Exception {
        CompletableFuture<Void> failed = new CompletableFuture<>();
        ViewerUdpLink link = open(LinkImpairment.NONE, rect -> {}, () -> failed.complete(null));
        agent.receiveUntil(p -> p.type() == Type.PROBE, WAIT).orElseThrow();
        AtomicInteger polls = new AtomicInteger();
        link.faults(new ViewerUdpLink.Faults() {
            @Override
            public void beforePoll() {
                polls.incrementAndGet();
                throw new IllegalStateException("injected poll failure");
            }
        });

        agent.send(Type.TILE, RectFragmenter.split(rect(1, 100)).getFirst().encode()); // makes an ACK due

        failed.get(WAIT.toMillis(), TimeUnit.MILLISECONDS); // retried every 25 ms, failed on the third
        assertThat(polls.get()).isEqualTo(ViewerUdpLink.MAX_CONSECUTIVE_FAILURES);
        assertThat(poll(link::closed, WAIT)).isTrue();
        Thread.sleep(200); // ~8 retry periods: no timer is left
        assertThat(polls.get()).isEqualTo(ViewerUdpLink.MAX_CONSECUTIVE_FAILURES);
    }

    @Test
    void pollFailuresBelowTheLimitAreRetried() throws Exception {
        CompletableFuture<Void> failed = new CompletableFuture<>();
        ViewerUdpLink link = open(LinkImpairment.NONE, rect -> {}, () -> failed.complete(null));
        agent.receiveUntil(p -> p.type() == Type.PROBE, WAIT).orElseThrow();
        AtomicInteger polls = new AtomicInteger();
        link.faults(new ViewerUdpLink.Faults() {
            @Override
            public void beforePoll() {
                if (polls.incrementAndGet() < ViewerUdpLink.MAX_CONSECUTIVE_FAILURES) {
                    throw new IllegalStateException("injected poll failure " + polls.get());
                }
            }
        });

        agent.send(Type.TILE, RectFragmenter.split(rect(1, 100)).getFirst().encode());

        assertThat(agent.receiveUntil(p -> p.type() == Type.ACK
                && ackOf(p).packets().equals(List.of(new Range(1, 1))), WAIT)).isPresent(); // sent by the retry
        assertThat(polls.get()).isGreaterThanOrEqualTo(ViewerUdpLink.MAX_CONSECUTIVE_FAILURES);
        assertThat(failed).isNotDone();
        assertThat(link.closed()).isFalse();
    }

    @Test
    void persistentPacketFailureEndsTheSession() throws Exception {
        CompletableFuture<Void> failed = new CompletableFuture<>();
        ViewerUdpLink link = open(LinkImpairment.NONE, rect -> {}, () -> failed.complete(null));
        agent.receiveUntil(p -> p.type() == Type.PROBE, WAIT).orElseThrow();
        AtomicInteger packets = new AtomicInteger();
        link.faults(new ViewerUdpLink.Faults() {
            @Override
            public void beforePacket() {
                packets.incrementAndGet();
                throw new IllegalStateException("injected packet failure");
            }
        });

        for (int i = 1; i <= ViewerUdpLink.MAX_CONSECUTIVE_FAILURES; i++) {
            agent.send(Type.TILE, RectFragmenter.split(rect(i, 100)).getFirst().encode());
        }

        failed.get(WAIT.toMillis(), TimeUnit.MILLISECONDS);
        assertThat(packets.get()).isEqualTo(ViewerUdpLink.MAX_CONSECUTIVE_FAILURES);
        assertThat(poll(link::closed, WAIT)).isTrue();
    }

    @Test
    void acksTilePackets() throws Exception {
        open(LinkImpairment.NONE);
        agent.receiveUntil(p -> p.type() == Type.PROBE, WAIT).orElseThrow(); // learns the viewer's address
        List<TileFragment> fragments = RectFragmenter.split(rect(1, 3000));

        agent.send(Type.TILE, fragments.get(0).encode()); // number 1
        agent.send(Type.TILE, fragments.get(1).encode()); // number 2

        // (A stalled test thread may see packet 1 acknowledged alone first, after the ack delay.)
        UdpPacket ack = agent.receiveUntil(p -> p.type() == Type.ACK
                && ackOf(p).packets().equals(List.of(new Range(1, 2))), WAIT).orElseThrow();
        assertThat(ack.from()).isEqualTo(Direction.VIEWER);
        assertThat(ackOf(ack).doneRects()).isEmpty();
        // A single packet is acknowledged too, at the latest after the ack delay.
        agent.send(Type.TILE, fragments.get(0).encode()); // number 3, a duplicate fragment
        assertThat(agent.receiveUntil(p -> p.type() == Type.ACK
                && ackOf(p).packets().equals(List.of(new Range(1, 3))), WAIT)).isPresent();
    }

    @Test
    void completeRectDelivered() throws Exception {
        List<CompleteRect> got = new CopyOnWriteArrayList<>();
        open(LinkImpairment.NONE, got::add);
        agent.receiveUntil(p -> p.type() == Type.PROBE, WAIT).orElseThrow();
        CompleteRect whole = rect(7, 3000);
        List<TileFragment> fragments = RectFragmenter.split(whole);
        assertThat(fragments).hasSize(3);

        agent.send(Type.TILE, fragments.get(2).encode());
        agent.send(Type.TILE, fragments.get(0).encode());
        agent.send(Type.TILE, fragments.get(1).encode());

        assertThat(poll(() -> !got.isEmpty(), WAIT)).isTrue();
        assertThat(got).containsExactly(whole);
    }

    @Test
    void appliedReportedInAck() throws Exception {
        List<CompleteRect> got = new CopyOnWriteArrayList<>();
        ViewerUdpLink link = open(LinkImpairment.NONE, got::add);
        agent.receiveUntil(p -> p.type() == Type.PROBE, WAIT).orElseThrow();
        agent.send(Type.TILE, RectFragmenter.split(rect(5, 100)).getFirst().encode());
        agent.send(Type.TILE, RectFragmenter.split(rect(6, 100)).getFirst().encode());
        assertThat(poll(() -> got.size() == 2, WAIT)).isTrue();
        agent.receiveUntil(p -> p.type() == Type.ACK, WAIT).orElseThrow(); // the packets' own ACK

        link.applied(5); // from the test thread, not the event loop
        link.dropped(6);

        // Done ranges are cumulative and drops repeat in 3 ACKs, so one ACK carries both reports.
        UdpPacket ack = agent.receiveUntil(p -> p.type() == Type.ACK
                && !ackOf(p).doneRects().isEmpty() && !ackOf(p).droppedRects().isEmpty(), WAIT).orElseThrow();
        assertThat(ackOf(ack).doneRects()).containsExactly(new Range(5, 5));
        assertThat(ackOf(ack).droppedRects()).containsExactly(6L);
    }

    @Test
    void agentProbeIsAcknowledged() throws Exception {
        open(LinkImpairment.NONE);
        agent.receiveUntil(p -> p.type() == Type.PROBE, WAIT).orElseThrow();

        agent.send(Type.PROBE, ProbePayload.encode(1, 8)); // number 1: answered and acknowledged

        UdpPacket ack = agent.receiveUntil(p -> p.type() == Type.ACK, WAIT).orElseThrow();
        assertThat(ackOf(ack).packets()).containsExactly(new Range(1, 1));
    }

    @Test
    void closeStopsAcks() throws Exception {
        ViewerUdpLink link = open(LinkImpairment.NONE);
        agent.receiveUntil(p -> p.type() == Type.PROBE, WAIT).orElseThrow();
        agent.send(Type.TILE, RectFragmenter.split(rect(1, 3000)).getFirst().encode()); // incomplete: expiry timer

        link.close();
        link.applied(1); // after close: ignored, never throws

        agent.collect(Duration.ofMillis(300)); // drains datagrams already on their way
        assertThat(agent.collect(Duration.ofMillis(700))).isEmpty();
    }

    @Test
    void probesUntilReply() throws Exception {
        ViewerUdpLink link = open(LinkImpairment.NONE);

        List<UdpPacket> got = agent.collect(Duration.ofMillis(700));

        assertThat(got).hasSizeGreaterThanOrEqualTo(3);
        assertThat(got).allSatisfy(p -> {
            assertThat(p.type()).isEqualTo(Type.PROBE);
            assertThat(p.from()).isEqualTo(Direction.VIEWER);
            assertThat(p.sessionId()).isEqualTo(SESSION_ID);
        });
        for (int i = 1; i < got.size(); i++) {
            assertThat(got.get(i).number()).isGreaterThan(got.get(i - 1).number());
        }
        assertThat(link.established()).isFalse();
        assertThat(link.lastRttNanos()).isEmpty();
    }

    @Test
    void slowsDownAfterReply() throws Exception {
        ViewerUdpLink link = open(LinkImpairment.NONE);
        UdpPacket probe = agent.receiveUntil(p -> p.type() == Type.PROBE, WAIT).orElseThrow();

        agent.send(Type.PROBE_REPLY, ProbePayload.encode(ProbePayload.sentNanos(probe.payload()).orElseThrow(), 8));

        assertThat(poll(link::established, WAIT)).isTrue();
        assertThat(link.lastRttNanos()).isPresent();
        assertThat(link.lastRttNanos().getAsLong()).isPositive();
        // Probes sent before the link noticed the reply may still be on their way: count only later ones.
        long establishedSeen = System.nanoTime();
        long later = agent.collect(Duration.ofMillis(1500)).stream()
                .filter(p -> p.type() == Type.PROBE)
                .filter(p -> ProbePayload.sentNanos(p.payload()).orElseThrow() - establishedSeen > 0)
                .count();
        assertThat(later).isLessThanOrEqualTo(2);
    }

    @Test
    void answersAgentProbe() throws Exception {
        open(LinkImpairment.NONE);
        agent.receiveUntil(p -> p.type() == Type.PROBE, WAIT).orElseThrow(); // learns the viewer's address

        agent.send(Type.PROBE, ProbePayload.encode(0x0102_0304_0506_0708L, 8));

        UdpPacket reply = agent.receiveUntil(p -> p.type() == Type.PROBE_REPLY, WAIT).orElseThrow();
        assertThat(reply.from()).isEqualTo(Direction.VIEWER);
        assertThat(ProbePayload.sentNanos(reply.payload())).hasValue(0x0102_0304_0506_0708L);
    }

    @Test
    void ignoresGarbage() throws Exception {
        ViewerUdpLink link = open(LinkImpairment.NONE);
        UdpPacket probe = agent.receiveUntil(p -> p.type() == Type.PROBE, WAIT).orElseThrow();
        byte[] echo = ProbePayload.encode(ProbePayload.sentNanos(probe.payload()).orElseThrow(), 8);

        agent.sendRaw(new byte[] {1, 2, 3});
        agent.sendRaw(new byte[2000]);
        agent.sendRaw(new byte[100]);
        // Valid-looking replies that must not count: wrong key, wrong direction, wrong session, bit-flipped MAC.
        agent.sendRaw(new UdpPacketCodec(key(8)).encode(
                new UdpPacket(Type.PROBE_REPLY, Direction.AGENT, SESSION_ID, 100, echo)));
        agent.sendRaw(agent.codec.encode(new UdpPacket(Type.PROBE_REPLY, Direction.VIEWER, SESSION_ID, 101, echo)));
        agent.sendRaw(agent.codec.encode(new UdpPacket(Type.PROBE_REPLY, Direction.AGENT, SESSION_ID + 1, 102, echo)));
        byte[] flipped = agent.codec.encode(new UdpPacket(Type.PROBE_REPLY, Direction.AGENT, SESSION_ID, 103, echo));
        flipped[flipped.length - 1] ^= 1;
        agent.sendRaw(flipped);
        // A reply that does not echo one of the viewer's probes proves nothing.
        long longBefore = ProbePayload.sentNanos(probe.payload()).orElseThrow() - Duration.ofHours(1).toNanos();
        agent.send(Type.PROBE_REPLY, ProbePayload.encode(longBefore, 8));

        // The socket is still alive and answers a valid probe.
        byte[] replayed = agent.codec.encode(new UdpPacket(Type.PROBE, Direction.AGENT, SESSION_ID, 500,
                ProbePayload.encode(77, 8)));
        agent.sendRaw(replayed);
        assertThat(agent.receiveUntil(p -> p.type() == Type.PROBE_REPLY, WAIT)).isPresent();
        assertThat(link.established()).isFalse();

        // The same packet again is a replay: no second answer.
        agent.sendRaw(replayed);
        assertThat(agent.collect(Duration.ofMillis(500))).noneMatch(p -> p.type() == Type.PROBE_REPLY);
        assertThat(link.established()).isFalse();
    }

    @Test
    void blockedOutboundSendsNothing() throws Exception {
        open(new LinkImpairment(0, 0, Duration.ZERO, Duration.ZERO, false, true, 0));

        assertThat(agent.collect(Duration.ofMillis(700))).isEmpty();
    }

    @Test
    void survivesPortUnreachable() throws Exception {
        int deadPort;
        try (DatagramSocket dead = new DatagramSocket(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0))) {
            deadPort = dead.getLocalPort();
        }
        ViewerUdpLink link = ViewerUdpLink.open(group, new InetSocketAddress(InetAddress.getLoopbackAddress(), deadPort),
                new UdpSetup(deadPort, SESSION_ID, key), LinkImpairment.NONE);
        links.add(link);
        Thread.sleep(600); // probes hit the closed port: ICMP port unreachable on the connected socket

        assertThat(link.closed()).isFalse();
        agent = new FakeUdpAgent(key, deadPort);
        assertThat(agent.receiveUntil(p -> p.type() == Type.PROBE, WAIT)).isPresent();
        assertThat(link.closed()).isFalse();
    }

    @Test
    void closeClosesUdpChannel() throws Exception {
        ViewerUdpLink link = open(LinkImpairment.NONE);
        agent.receiveUntil(p -> p.type() == Type.PROBE, WAIT).orElseThrow();
        int viewerPort = agent.lastSender.getPort();

        link.close();
        link.close(); // idempotent

        assertThat(link.closed()).isTrue();
        agent.collect(Duration.ofMillis(300)); // drains datagrams already on their way
        assertThat(agent.collect(Duration.ofMillis(700))).isEmpty();
        try (DatagramSocket rebound = new DatagramSocket(
                new InetSocketAddress(InetAddress.getLoopbackAddress(), viewerPort))) {
            assertThat(rebound.getLocalPort()).isEqualTo(viewerPort);
        }
    }

    @Test
    void closeOnTheEventLoopDoesNotBlock() throws Exception {
        ViewerUdpLink link = open(LinkImpairment.NONE);
        agent.receiveUntil(p -> p.type() == Type.PROBE, WAIT).orElseThrow();

        group.next().submit(link::close).get(WAIT.toMillis(), TimeUnit.MILLISECONDS);

        assertThat(poll(link::closed, WAIT)).isTrue();
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

    /** The agent's end: a raw loopback socket with the session's codec. Test thread only. */
    private static final class FakeUdpAgent implements AutoCloseable {
        final UdpPacketCodec codec;
        private final DatagramSocket socket;
        private long nextNumber = 1;
        /** Sender of the latest viewer packet; the agent answers there. */
        InetSocketAddress lastSender;

        FakeUdpAgent(byte[] key) throws IOException {
            this(key, 0);
        }

        FakeUdpAgent(byte[] key, int port) throws IOException {
            codec = new UdpPacketCodec(key);
            socket = new DatagramSocket(new InetSocketAddress(InetAddress.getLoopbackAddress(), port));
        }

        int port() {
            return socket.getLocalPort();
        }

        InetSocketAddress address() {
            return new InetSocketAddress(InetAddress.getLoopbackAddress(), port());
        }

        void send(Type type, byte[] payload) throws IOException {
            sendRaw(codec.encode(new UdpPacket(type, Direction.AGENT, SESSION_ID, nextNumber++, payload)));
        }

        void sendRaw(byte[] wire) throws IOException {
            socket.send(new DatagramPacket(wire, wire.length, lastSender));
        }

        /** Next viewer packet within {@code within}, or empty; undecodable datagrams are skipped. */
        Optional<UdpPacket> receive(Duration within) throws IOException {
            long deadline = System.nanoTime() + within.toNanos();
            while (true) {
                long left = deadline - System.nanoTime();
                if (left <= 0) {
                    return Optional.empty();
                }
                byte[] buf = new byte[2048];
                DatagramPacket dp = new DatagramPacket(buf, buf.length);
                socket.setSoTimeout((int) Math.max(1, left / 1_000_000));
                try {
                    socket.receive(dp);
                } catch (SocketTimeoutException e) {
                    return Optional.empty();
                }
                Optional<UdpPacket> p = codec.decode(ByteBuffer.wrap(Arrays.copyOf(buf, dp.getLength())),
                        SESSION_ID, Direction.VIEWER);
                if (p.isPresent()) {
                    lastSender = (InetSocketAddress) dp.getSocketAddress();
                    return p;
                }
            }
        }

        Optional<UdpPacket> receiveUntil(Predicate<UdpPacket> match, Duration within) throws IOException {
            long deadline = System.nanoTime() + within.toNanos();
            while (true) {
                Optional<UdpPacket> p = receive(Duration.ofNanos(Math.max(0, deadline - System.nanoTime())));
                if (p.isEmpty() || match.test(p.get())) {
                    return p;
                }
            }
        }

        /** Every viewer packet arriving within {@code window}. */
        List<UdpPacket> collect(Duration window) throws IOException {
            long deadline = System.nanoTime() + window.toNanos();
            List<UdpPacket> got = new ArrayList<>();
            while (true) {
                Optional<UdpPacket> p = receive(Duration.ofNanos(Math.max(0, deadline - System.nanoTime())));
                if (p.isEmpty()) {
                    return got;
                }
                got.add(p.get());
            }
        }

        @Override
        public void close() {
            socket.close();
        }
    }
}

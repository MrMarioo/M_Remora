package pl.mtech.mremora.agent;

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
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import pl.mtech.mremora.protocol.TransportState.Mode;
import pl.mtech.mremora.protocol.netty.LinkImpairment;
import pl.mtech.mremora.protocol.udp.ProbePayload;
import pl.mtech.mremora.protocol.udp.UdpPacket;
import pl.mtech.mremora.protocol.udp.UdpPacket.Direction;
import pl.mtech.mremora.protocol.udp.UdpPacket.Type;
import pl.mtech.mremora.protocol.udp.UdpPacketCodec;
import pl.mtech.mremora.testkit.MutableClock;

class UdpEndpointTest {

    private static final Duration WAIT = Duration.ofSeconds(5);
    /** How long a "nothing arrives" check listens; a lower bound only. */
    private static final Duration QUIET = Duration.ofMillis(500);
    private static final int SESSION = 0x1234_5678;

    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-27T10:00:00Z"));
    private final List<Viewer> viewers = new ArrayList<>();
    private EventLoopGroup group;
    private UdpEndpoint endpoint;

    @BeforeEach
    void setUp() {
        group = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
        endpoint = UdpEndpoint.bind(group, 0, LinkImpairment.NONE).orElseThrow();
    }

    @AfterEach
    void tearDown() {
        viewers.forEach(Viewer::close);
        endpoint.close();
        group.shutdownGracefully(0, 1, TimeUnit.SECONDS).syncUninterruptibly();
    }

    @Test
    void probeGetsReply() throws Exception {
        Session s = attachAndStart(SESSION, clock::nanoTime);
        Viewer v = viewer(s.key);

        v.send(probe(SESSION, 1, 123));

        UdpPacket reply = v.receiveUntil(p -> p.type() == Type.PROBE_REPLY);
        assertThat(reply.from()).isEqualTo(Direction.AGENT);
        assertThat(reply.sessionId()).isEqualTo(SESSION);
        assertThat(ProbePayload.sentNanos(reply.payload())).hasValue(123);
    }

    @Test
    void agentProbesViewerAfterLearningAddress() throws Exception {
        Session s = attachAndStart(SESSION, clock::nanoTime);
        Viewer v = viewer(s.key);

        v.send(probe(SESSION, 1, 7));
        UdpPacket agentProbe = v.receiveUntil(p -> p.type() == Type.PROBE);
        assertThat(agentProbe.from()).isEqualTo(Direction.AGENT);
        assertThat(onLoop(s.link::viewerAddress)).contains(v.address());

        long sent = ProbePayload.sentNanos(agentProbe.payload()).orElseThrow();
        v.send(new UdpPacket(Type.PROBE_REPLY, Direction.VIEWER, SESSION, 2, ProbePayload.encode(sent, 8)));

        assertThat(s.decided.get(WAIT.toMillis(), TimeUnit.MILLISECONDS)).isEqualTo(Mode.UDP);
        OptionalLong rtt = onLoop(s.link::lastRttNanos);
        assertThat(rtt).isPresent();
        assertThat(rtt.getAsLong()).isNotNegative();
    }

    @Test
    void unsolicitedReplyDoesNotDecideUdp() throws Exception {
        Session s = attachAndStart(SESSION, clock::nanoTime);
        Viewer v = viewer(s.key);

        // A reply before the agent ever probed proves nothing about the agent -> viewer leg.
        v.send(new UdpPacket(Type.PROBE_REPLY, Direction.VIEWER, SESSION, 1,
                ProbePayload.encode(System.nanoTime(), 8)));
        v.receiveUntil(p -> p.type() == Type.PROBE); // processed: the agent learned the address and probes

        clock.advance(UdpStartup.TIMEOUT);
        assertThat(s.decided.get(WAIT.toMillis(), TimeUnit.MILLISECONDS)).isEqualTo(Mode.TCP);
    }

    @Test
    void noUdpDecidesTcp() throws Exception {
        long started = System.nanoTime();
        Session s = attachAndStart(SESSION, System::nanoTime);

        assertThat(s.decided.get(WAIT.toMillis(), TimeUnit.MILLISECONDS)).isEqualTo(Mode.TCP);
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isGreaterThanOrEqualTo(Duration.ofMillis(1900));
    }

    @Test
    void decidedExactlyOnce() throws Exception {
        List<Mode> calls = new CopyOnWriteArrayList<>();
        byte[] key = key();
        UdpSessionLink link = new UdpSessionLink(endpoint, SESSION, key,
                clock::nanoTime, UdpStartup.TIMEOUT, calls::add);
        onLoop(() -> {
            endpoint.attach(link);
            link.start();
            return null;
        });
        clock.advance(UdpStartup.TIMEOUT);
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (calls.isEmpty() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        Thread.sleep(QUIET.toMillis());
        assertThat(calls).containsExactly(Mode.TCP);
    }

    @Test
    void detachedLinkNeverDecides() throws Exception {
        Session s = attachAndStart(SESSION, clock::nanoTime);
        onLoop(() -> {
            endpoint.detach(s.link);
            return null;
        });
        clock.advance(Duration.ofSeconds(10));
        Thread.sleep(QUIET.toMillis());
        assertThat(s.decided).isNotDone();
    }

    @Test
    void garbageGetsNoReply() throws Exception {
        Session s = attachAndStart(SESSION, clock::nanoTime);
        Viewer v = viewer(s.key);
        Viewer scanner = viewer(s.key); // separate socket: anything it provoked would come back to it
        Random random = new Random(1);
        byte[] noise = new byte[100];
        random.nextBytes(noise);
        byte[] otherKey = key();

        scanner.sendRaw(new byte[0]);
        scanner.sendRaw(new byte[30]);
        scanner.sendRaw(new byte[1201]);
        scanner.sendRaw(noise);
        // Good header, wrong key, and the number the valid probe below uses: it must not use up that number.
        scanner.sendRaw(new UdpPacketCodec(otherKey).encode(probe(SESSION, 2, 666)));

        assertThat(scanner.collect(QUIET)).isEmpty(); // no reply and no agent PROBE: its address was not learned
        assertThat(scanner.undecodable).isZero();
        assertThat(onLoop(s.link::viewerAddress)).isEmpty();

        v.send(probe(SESSION, 2, 42));

        List<UdpPacket> got = v.collectAfter(p -> p.type() == Type.PROBE_REPLY);
        // The agent also sends its own PROBEs once it learned the address; the only REPLY must answer the valid probe.
        assertThat(got).allMatch(p -> p.type() == Type.PROBE || p.type() == Type.PROBE_REPLY);
        List<UdpPacket> replies = got.stream().filter(p -> p.type() == Type.PROBE_REPLY).toList();
        assertThat(replies).hasSize(1);
        assertThat(ProbePayload.sentNanos(replies.getFirst().payload())).hasValue(42);
        assertThat(v.undecodable).isZero();
        assertThat(onLoop(s.link::viewerAddress)).contains(v.address());
        assertThat(scanner.collect(QUIET)).isEmpty();
    }

    @Test
    void reflectedProbeIgnored() throws Exception {
        Session s = attachAndStart(SESSION, clock::nanoTime);
        Viewer v = viewer(s.key);
        // A high number, so the agent's own PROBE #1 is still fresh in its replay window when it comes back:
        // only the signed direction can reject the reflection.
        v.send(probe(SESSION, 500, 11));
        byte[] agentProbe = v.receiveRawUntil(p -> p.type() == Type.PROBE);
        long agentSent = sentNanos(v.decode(agentProbe).orElseThrow());

        v.sendRaw(agentProbe);

        List<UdpPacket> got = v.collect(QUIET);
        assertThat(got).noneMatch(p -> p.type() == Type.PROBE_REPLY && sentNanos(p) == agentSent);
        assertThat(onLoop(s.link::viewerAddress)).contains(v.address());
        assertThat(onLoop(s.link::lastRttNanos)).isEmpty();
        assertThat(s.decided).isNotDone();
    }

    @Test
    void duplicateIgnored() throws Exception {
        Session s = attachAndStart(SESSION, clock::nanoTime);
        Viewer v = viewer(s.key);
        byte[] wire = v.codec.encode(probe(SESSION, 1, 5));

        v.sendRaw(wire);
        v.sendRaw(wire);

        List<UdpPacket> got = v.collectAfter(p -> p.type() == Type.PROBE_REPLY);
        assertThat(got.stream().filter(p -> p.type() == Type.PROBE_REPLY)).hasSize(1);
    }

    @Test
    void oldSessionPacketsDropped() throws Exception {
        Session a = attachAndStart(SESSION, clock::nanoTime);
        onLoop(() -> {
            endpoint.detach(a.link);
            return null;
        });
        Session b = attachAndStart(SESSION + 1, clock::nanoTime);
        Viewer v = viewer(a.key);

        v.send(probe(SESSION, 1, 1));

        assertThat(v.collect(QUIET)).isEmpty();
        assertThat(onLoop(b.link::viewerAddress)).isEmpty();
        assertThat(onLoop(a.link::viewerAddress)).isEmpty();
    }

    @Test
    void addressFollowsNewestPacket() throws Exception {
        Session s = attachAndStart(SESSION, clock::nanoTime);
        Viewer x = viewer(s.key);
        Viewer y = viewer(s.key);

        x.send(probe(SESSION, 5, 50));
        x.receiveUntil(p -> p.type() == Type.PROBE_REPLY);
        assertThat(onLoop(s.link::viewerAddress)).contains(x.address());

        y.send(probe(SESSION, 6, 60));
        y.receiveUntil(p -> p.type() == Type.PROBE_REPLY);
        assertThat(onLoop(s.link::viewerAddress)).contains(y.address());

        x.send(probe(SESSION, 4, 40));
        UdpPacket late = x.receiveUntil(p -> p.type() == Type.PROBE_REPLY && sentNanos(p) == 40);
        assertThat(late).isNotNull();
        assertThat(onLoop(s.link::viewerAddress)).contains(y.address());
    }

    @Test
    void noSessionDropsEverything() throws Exception {
        Viewer v = viewer(key());

        v.send(probe(SESSION, 1, 1));
        v.sendRaw(new byte[50]);

        assertThat(v.collect(QUIET)).isEmpty();
    }

    @Test
    void secondAttachIsRejected() throws Exception {
        attachAndStart(SESSION, clock::nanoTime);
        UdpSessionLink other = new UdpSessionLink(endpoint, SESSION + 1, key(),
                clock::nanoTime, UdpStartup.TIMEOUT, m -> {});
        CompletableFuture<Throwable> thrown = new CompletableFuture<>();
        endpoint.eventLoop().execute(() -> {
            try {
                endpoint.attach(other);
                thrown.complete(null);
            } catch (Throwable t) {
                thrown.complete(t);
            }
        });
        assertThat(thrown.get(WAIT.toMillis(), TimeUnit.MILLISECONDS)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void keyIsCopied() {
        byte[] key = key();
        UdpSessionLink link = new UdpSessionLink(endpoint, SESSION, key, clock::nanoTime, UdpStartup.TIMEOUT, m -> {});
        byte[] copy = key.clone();
        key[0] ^= 1;
        assertThat(link.key()).isEqualTo(copy);
        link.key()[1] ^= 1;
        assertThat(link.key()).isEqualTo(copy);
    }

    @Test
    void detachCancelsTimers() throws Exception {
        Session s = attachAndStart(SESSION, clock::nanoTime);
        Viewer v = viewer(s.key);
        v.send(probe(SESSION, 1, 1));
        v.receiveUntil(p -> p.type() == Type.PROBE); // probe timer scheduled as well as the decision tick
        assertThat(onLoop(s.link::probing)).isTrue();
        assertThat(onLoop(s.link::timersActive)).isTrue();

        endpoint.detach(s.link);

        assertThat(onLoop(s.link::timersActive)).isFalse();
    }

    @Test
    void closeCancelsTimers() throws Exception {
        Session s = attachAndStart(SESSION, clock::nanoTime);
        Viewer v = viewer(s.key);
        v.send(probe(SESSION, 1, 1));
        v.receiveUntil(p -> p.type() == Type.PROBE);
        assertThat(onLoop(s.link::probing)).isTrue();

        s.link.close();

        assertThat(onLoop(s.link::timersActive)).isFalse();
    }

    @Test
    void endpointCloseCancelsTimers() throws Exception {
        Session s = attachAndStart(SESSION, clock::nanoTime);
        assertThat(onLoop(s.link::timersActive)).isTrue(); // the decision tick

        endpoint.close();

        assertThat(onLoop(s.link::timersActive)).isFalse();
    }

    @Test
    void unexpectedCloseIsReported() throws Exception {
        CompletableFuture<Void> reported = new CompletableFuture<>();
        endpoint.onUnexpectedClose(() -> reported.complete(null));

        endpoint.closeSocketUnexpectedly();

        reported.get(WAIT.toMillis(), TimeUnit.MILLISECONDS);
    }

    @Test
    void ownCloseIsNotReported() throws Exception {
        CompletableFuture<Void> reported = new CompletableFuture<>();
        endpoint.onUnexpectedClose(() -> reported.complete(null));

        endpoint.close();

        Thread.sleep(QUIET.toMillis());
        assertThat(reported).isNotDone();
    }

    @Test
    void bindFailureIsEmpty() throws Exception {
        try (DatagramSocket taken = new DatagramSocket(0)) {
            assertThat(UdpEndpoint.bind(group, taken.getLocalPort(), LinkImpairment.NONE)).isEmpty();
        }
    }

    @Test
    void blockedImpairmentDropsInbound() throws Exception {
        UdpEndpoint blocked = UdpEndpoint.bind(group, 0, LinkImpairment.blocked()).orElseThrow();
        try {
            byte[] key = key();
            UdpSessionLink link = new UdpSessionLink(blocked, SESSION, key,
                    clock::nanoTime, UdpStartup.TIMEOUT, m -> {});
            blocked.eventLoop().submit(() -> {
                blocked.attach(link);
                link.start();
            }).get(WAIT.toMillis(), TimeUnit.MILLISECONDS);
            Viewer v = viewer(key, blocked.port());
            v.send(probe(SESSION, 1, 1));
            assertThat(v.collect(QUIET)).isEmpty();
        } finally {
            blocked.close();
        }
    }

    // --- helpers ---

    private record Session(UdpSessionLink link, byte[] key, CompletableFuture<Mode> decided) {}

    private Session attachAndStart(int sessionId, LongSupplier nanoTime) throws Exception {
        byte[] key = key();
        CompletableFuture<Mode> decided = new CompletableFuture<>();
        UdpSessionLink link = new UdpSessionLink(endpoint, sessionId, key,
                nanoTime, UdpStartup.TIMEOUT, decided::complete);
        onLoop(() -> {
            endpoint.attach(link);
            link.start();
            return null;
        });
        return new Session(link, key, decided);
    }

    private <T> T onLoop(Callable<T> task) throws Exception {
        return endpoint.eventLoop().submit(task).get(WAIT.toMillis(), TimeUnit.MILLISECONDS);
    }

    private Viewer viewer(byte[] key) throws IOException {
        return viewer(key, endpoint.port());
    }

    private Viewer viewer(byte[] key, int port) throws IOException {
        Viewer v = new Viewer(key, port);
        viewers.add(v);
        return v;
    }

    private static UdpPacket probe(int sessionId, long number, long sentNanos) {
        return new UdpPacket(Type.PROBE, Direction.VIEWER, sessionId, number, ProbePayload.encode(sentNanos, 64));
    }

    private static long sentNanos(UdpPacket p) {
        return ProbePayload.sentNanos(p.payload()).orElse(Long.MIN_VALUE);
    }

    private static byte[] key() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return key;
    }

    /** A raw "viewer" socket on loopback with its own codec. */
    private static final class Viewer {
        final UdpPacketCodec codec;
        final DatagramSocket socket;
        final InetSocketAddress agent;
        int undecodable;

        Viewer(byte[] key, int port) throws IOException {
            codec = new UdpPacketCodec(key);
            socket = new DatagramSocket(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            agent = new InetSocketAddress(InetAddress.getLoopbackAddress(), port);
        }

        InetSocketAddress address() {
            return (InetSocketAddress) socket.getLocalSocketAddress();
        }

        void send(UdpPacket p) throws IOException {
            sendRaw(codec.encode(p));
        }

        void sendRaw(byte[] bytes) throws IOException {
            socket.send(new DatagramPacket(bytes, bytes.length, agent));
        }

        /** Next datagram within {@code within}, or empty. */
        Optional<byte[]> receiveRaw(Duration within) throws IOException {
            if (within.isNegative() || within.isZero()) {
                return Optional.empty();
            }
            byte[] buf = new byte[2048];
            DatagramPacket dp = new DatagramPacket(buf, buf.length);
            socket.setSoTimeout((int) Math.max(1, within.toMillis()));
            try {
                socket.receive(dp);
            } catch (SocketTimeoutException e) {
                return Optional.empty();
            }
            return Optional.of(Arrays.copyOf(buf, dp.getLength()));
        }

        Optional<UdpPacket> decode(byte[] raw) {
            Optional<UdpPacket> p = codec.decode(ByteBuffer.wrap(raw), sessionOf(raw), Direction.AGENT);
            if (p.isEmpty()) {
                undecodable++;
            }
            return p;
        }

        private static int sessionOf(byte[] raw) {
            return raw.length >= 7 ? ByteBuffer.wrap(raw).getInt(3) : 0;
        }

        byte[] receiveRawUntil(Predicate<UdpPacket> wanted) throws IOException {
            long deadline = System.nanoTime() + WAIT.toNanos();
            while (true) {
                Optional<byte[]> raw = receiveRaw(Duration.ofNanos(deadline - System.nanoTime()));
                if (raw.isEmpty()) {
                    throw new AssertionError("wanted packet did not arrive within " + WAIT);
                }
                Optional<UdpPacket> p = decode(raw.get());
                if (p.isPresent() && wanted.test(p.get())) {
                    return raw.get();
                }
            }
        }

        UdpPacket receiveUntil(Predicate<UdpPacket> wanted) throws IOException {
            return decode(receiveRawUntil(wanted)).orElseThrow();
        }

        /** Every packet arriving within {@code window}. */
        List<UdpPacket> collect(Duration window) throws IOException {
            List<UdpPacket> out = new ArrayList<>();
            long deadline = System.nanoTime() + window.toNanos();
            while (true) {
                Optional<byte[]> raw = receiveRaw(Duration.ofNanos(deadline - System.nanoTime()));
                if (raw.isEmpty()) {
                    return out;
                }
                decode(raw.get()).ifPresent(out::add);
            }
        }

        /** Packets until (including) the first {@code wanted} one, then everything arriving in {@link #QUIET}. */
        List<UdpPacket> collectAfter(Predicate<UdpPacket> wanted) throws IOException {
            List<UdpPacket> out = new ArrayList<>();
            long deadline = System.nanoTime() + WAIT.toNanos();
            while (true) {
                Optional<byte[]> raw = receiveRaw(Duration.ofNanos(deadline - System.nanoTime()));
                if (raw.isEmpty()) {
                    throw new AssertionError("wanted packet did not arrive within " + WAIT);
                }
                Optional<UdpPacket> p = decode(raw.get());
                p.ifPresent(out::add);
                if (p.isPresent() && wanted.test(p.get())) {
                    break;
                }
            }
            out.addAll(collect(QUIET));
            return out;
        }

        void close() {
            socket.close();
        }
    }
}

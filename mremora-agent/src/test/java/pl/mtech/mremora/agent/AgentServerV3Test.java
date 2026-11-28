package pl.mtech.mremora.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import pl.mtech.mremora.protocol.Bye;
import pl.mtech.mremora.protocol.FrameAck;
import pl.mtech.mremora.protocol.Heartbeat;
import pl.mtech.mremora.protocol.Hello;
import pl.mtech.mremora.protocol.Message;
import pl.mtech.mremora.protocol.TileUpdate;
import pl.mtech.mremora.protocol.TransportState;
import pl.mtech.mremora.protocol.UdpSetup;
import pl.mtech.mremora.protocol.udp.ProbePayload;
import pl.mtech.mremora.protocol.udp.TileFragment;
import pl.mtech.mremora.protocol.udp.UdpPacket;
import pl.mtech.mremora.protocol.udp.UdpPacket.Direction;
import pl.mtech.mremora.protocol.udp.UdpPacket.Type;
import pl.mtech.mremora.protocol.udp.UdpPacketCodec;
import pl.mtech.mremora.testkit.MutableClock;
import pl.mtech.mremora.testkit.WireClient;

/**
 * The v3 handshake over loopback: {@code UdpSetup}, the UDP probe phase, then {@code TransportState}: UDP (the picture
 * as {@code TILE} packets) when a probe round trip succeeded, otherwise TCP (the v2 tile stream).
 */
class AgentServerV3Test {

    /** Generous upper bound for anything the server should do "now"; only reached when a test fails. */
    private static final Duration WAIT = Duration.ofSeconds(5);
    private static final Duration LONG = Duration.ofSeconds(60);
    /** Startup timeout for tests that do not measure it, so they need not wait 2 s. */
    private static final Duration SHORT_STARTUP = Duration.ofMillis(300);
    /** Time for datagrams already on their way to arrive before a "nothing more" window starts. */
    private static final Duration GRACE = Duration.ofMillis(300);
    private static final TransportState TCP = new TransportState(TransportState.Mode.TCP, 0);

    private final MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
    private final RecordingUi ui = new RecordingUi();
    private final List<WireClient> clients = new ArrayList<>();
    private final List<UdpViewer> udpViewers = new ArrayList<>();
    private final FakeScreen screen = new FakeScreen(32, 16);
    private AgentServer server;
    private int port;
    /** {@link System#nanoTime()} just before the latest {@link #loginV3()} connected. */
    private long loginStarted;

    @AfterEach
    void tearDown() {
        clients.forEach(WireClient::close);
        udpViewers.forEach(UdpViewer::close);
        if (server != null) {
            server.close();
        }
    }

    /** Offers v3 on any free port; long heartbeat and idle, so nothing arrives unasked. */
    private void startServer(Duration udpStartupTimeout) {
        startServer(0, udpStartupTimeout);
    }

    private void startServer(int tcpPort, Duration udpStartupTimeout) {
        AgentConfig config = new AgentConfig(tcpPort, Duration.ofSeconds(30), LONG, LONG, 30, 3);
        server = new AgentServer(config, screen, screen, new PairingCodes(clock, new Random(1)), ui);
        server.udpStartupTimeout(udpStartupTimeout);
        port = server.start();
    }

    private WireClient connect() {
        WireClient client = WireClient.connect(port);
        clients.add(client);
        return client;
    }

    /** Logs in as a v3 viewer and returns the agent's {@code UdpSetup}. */
    private V3Login loginV3() throws InterruptedException {
        loginStarted = System.nanoTime();
        WireClient client = connect();
        client.send(new Hello(3, Hello.Role.VIEWER, ui.lastCode()));
        assertThat(client.receive(WAIT)).isEqualTo(new Hello(3, Hello.Role.AGENT, ""));
        assertThat(client.receive(WAIT)).isEqualTo(screen.info());
        Message m = client.receive(WAIT);
        assertThat(m).isInstanceOf(UdpSetup.class);
        assertThat(ui.awaitCall("session ", WAIT)).isTrue();
        return new V3Login(client, (UdpSetup) m);
    }

    private record V3Login(WireClient tcp, UdpSetup setup) {}

    private UdpViewer udpViewer(UdpSetup setup) throws IOException {
        UdpViewer v = new UdpViewer(setup);
        udpViewers.add(v);
        return v;
    }

    /** Next message that is not a heartbeat. */
    private static Message next(WireClient client) {
        while (true) {
            Message m = client.receive(WAIT);
            if (!(m instanceof Heartbeat)) {
                return m;
            }
        }
    }

    /** Reads the parts of one frame up to {@code lastPart}. */
    private static List<TileUpdate> receiveFrame(WireClient client) {
        List<TileUpdate> parts = new ArrayList<>();
        while (parts.isEmpty() || !parts.getLast().lastPart()) {
            Message m = next(client);
            assertThat(m).isInstanceOf(TileUpdate.class);
            parts.add((TileUpdate) m);
        }
        return parts;
    }

    private static BufferedImage filled(int width, int height, int rgb) {
        var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.setRGB(x, y, rgb);
            }
        }
        return image;
    }

    private static boolean await(Supplier<Boolean> condition) throws InterruptedException {
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.get()) {
                return true;
            }
            Thread.sleep(10);
        }
        return condition.get();
    }

    /** Waits until the UI shows a pairing code other than {@code used}: the previous session is over. */
    private void awaitNewCode(String used) throws InterruptedException {
        assertThat(await(() -> ui.calls().getLast().startsWith("code ")
                && !ui.calls().getLast().equals("code " + used))).isTrue();
    }

    /** Makes the agent learn the viewer's address and returns once it probed it. */
    private static void startProbing(UdpViewer v) throws IOException {
        v.send(Type.PROBE, ProbePayload.encode(System.nanoTime(), 8));
        assertThat(v.receiveUntil(p -> p.type() == Type.PROBE, WAIT)).isPresent();
    }

    @Test
    void v3ViewerGetsUdpSetup() throws InterruptedException {
        startServer(SHORT_STARTUP);

        UdpSetup setup = loginV3().setup();

        assertThat(server.udpPort()).isPositive();
        assertThat(setup.udpPort()).isEqualTo(server.udpPort());
        assertThat(setup.key()).hasSize(32);
    }

    @Test
    void withoutUdpTransportStateComesAfterTimeout() throws InterruptedException {
        startServer(UdpStartup.TIMEOUT);
        V3Login login = loginV3();

        assertThat(next(login.tcp())).isEqualTo(TCP);
        // Measured from before the login, so a slow test thread can only make it longer.
        Duration waited = Duration.ofNanos(System.nanoTime() - loginStarted);

        assertThat(waited).isGreaterThanOrEqualTo(Duration.ofMillis(1800));
        assertThat(receiveFrame(login.tcp())).isNotEmpty();
        assertThat(server.lastUdpOutcome()).contains(TransportState.Mode.TCP);
    }

    /** Answers the agent's first probe, so the probe phase decides UDP (long timeout: the reply decides). */
    private static void answerProbe(UdpViewer v) throws IOException {
        v.send(Type.PROBE, ProbePayload.encode(System.nanoTime(), 8));
        UdpPacket agentProbe = v.receiveUntil(p -> p.type() == Type.PROBE, WAIT).orElseThrow();
        long sent = ProbePayload.sentNanos(agentProbe.payload()).orElseThrow();
        v.send(Type.PROBE_REPLY, ProbePayload.encode(sent, 8));
    }

    /** The next message other than a heartbeat within {@code within}, or empty. */
    private static Optional<Message> nextWithin(WireClient client, Duration within) {
        long deadline = System.nanoTime() + within.toNanos();
        while (true) {
            long left = deadline - System.nanoTime();
            if (left <= 0) {
                return Optional.empty();
            }
            Message m;
            try {
                m = client.receive(Duration.ofNanos(left));
            } catch (AssertionError e) {
                return Optional.empty(); // nothing within the window
            }
            if (!(m instanceof Heartbeat)) {
                return Optional.of(m);
            }
        }
    }

    /** {@code TILE} fragments arriving within {@code window}. */
    private static List<TileFragment> fragments(UdpViewer v, Duration window) throws IOException {
        List<TileFragment> out = new ArrayList<>();
        for (UdpPacket p : v.collect(window)) {
            if (p.type() == Type.TILE) {
                out.add(TileFragment.decode(p.payload()).orElseThrow());
            }
        }
        return out;
    }

    @Test
    void udpDecisionAnnouncesUdp() throws Exception {
        startServer(LONG);
        V3Login login = loginV3();
        UdpViewer v = udpViewer(login.setup());

        answerProbe(v);

        assertThat(next(login.tcp())).isEqualTo(new TransportState(TransportState.Mode.UDP, 0));
        assertThat(server.lastUdpOutcome()).contains(TransportState.Mode.UDP);
        TileFragment first = v.receiveUntil(p -> p.type() == Type.TILE, WAIT)
                .flatMap(p -> TileFragment.decode(p.payload())).orElseThrow();
        assertThat(first.epoch()).isZero();
        assertThat(first.streamWidth()).isEqualTo(32);
        assertThat(first.streamHeight()).isEqualTo(16);
        assertThat(first.captureSeq()).isZero();
        // A change goes over UDP too (no FrameAck needed); nothing of the picture comes over TCP.
        screen.setImage(filled(32, 16, 0x336699));
        assertThat(v.receiveUntil(p -> p.type() == Type.TILE
                && TileFragment.decode(p.payload()).orElseThrow().captureSeq() == 1, WAIT)).isPresent();
        assertThat(nextWithin(login.tcp(), GRACE)).isEmpty();
        assertThat(await(() -> server.udpImageStats().orElseThrow().packetsSent() >= 2)).isTrue();
    }

    @Test
    void tcpDecisionStillV2() throws Exception {
        startServer(SHORT_STARTUP);
        V3Login login = loginV3();
        UdpViewer v = udpViewer(login.setup());
        startProbing(v); // the agent probes the viewer, which never answers: TCP by the timeout

        assertThat(next(login.tcp())).isEqualTo(TCP);
        assertThat(server.lastUdpOutcome()).contains(TransportState.Mode.TCP);
        long first = receiveFrame(login.tcp()).getFirst().sequence();
        screen.setImage(filled(32, 16, 0x336699));
        login.tcp().send(new FrameAck(first));
        assertThat(receiveFrame(login.tcp()).getFirst().sequence()).isEqualTo(first + 1);
        assertThat(fragments(v, GRACE)).isEmpty();
    }

    @Test
    void sessionEndReleasesTheUdpCaptureThread() throws Exception {
        Set<Thread> before = captureThreads();
        startServer(LONG);
        V3Login login = loginV3();
        UdpViewer v = udpViewer(login.setup());
        answerProbe(v);
        assertThat(next(login.tcp())).isEqualTo(new TransportState(TransportState.Mode.UDP, 0));
        // A viewer that never acknowledges: after two captures the gate stays closed and the thread parks.
        assertThat(v.receiveUntil(p -> p.type() == Type.TILE, WAIT)).isPresent();
        screen.setImage(filled(32, 16, 0x336699));
        assertThat(v.receiveUntil(p -> p.type() == Type.TILE
                && TileFragment.decode(p.payload()).orElseThrow().captureSeq() == 1, WAIT)).isPresent();
        Set<Thread> mine = captureThreads();
        mine.removeAll(before);
        assertThat(mine).hasSize(1);
        Thread capture = mine.iterator().next();
        assertThat(await(() -> capture.getState() == Thread.State.WAITING)).isTrue();
        int captures = screen.captures();
        Thread.sleep(GRACE.toMillis());
        assertThat(screen.captures()).isEqualTo(captures); // parked at the gate, not capturing
        assertThat(capture.getState()).isEqualTo(Thread.State.WAITING);

        ui.endSession();

        assertThat(login.tcp().receiveUntilClosed(WAIT)).containsExactly(new Bye("ended by user"));
        capture.join(WAIT.toMillis());
        assertThat(capture.isAlive()).isFalse();
        assertThat(screen.captures()).isEqualTo(captures);
        Thread.sleep(GRACE.toMillis());
        v.drain();
        assertThat(v.collect(Duration.ofMillis(1500))).isEmpty(); // no TILE, no probe after the end
    }

    private static Set<Thread> captureThreads() {
        Set<Thread> out = new HashSet<>();
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            if (t.getName().equals("mremora-capture") && t.isAlive()) {
                out.add(t);
            }
        }
        return out;
    }

    @Test
    void viewerLeavesDuringUdpWait() throws Exception {
        startServer(UdpStartup.TIMEOUT);
        String used = ui.lastCode();
        V3Login login = loginV3();
        UdpViewer v = udpViewer(login.setup());
        startProbing(v);

        login.tcp().close();
        awaitNewCode(used);
        Thread.sleep(GRACE.toMillis());
        v.drain();

        assertThat(v.collect(Duration.ofMillis(2500))).noneMatch(p -> p.type() == Type.PROBE);
        assertThat(server.lastUdpOutcome()).isEmpty();
        UdpSetup second = loginV3().setup();
        assertThat(second.udpPort()).isEqualTo(server.udpPort());
    }

    @Test
    void endSessionDuringUdpWait() throws Exception {
        startServer(LONG);
        V3Login login = loginV3();

        ui.endSession();

        assertThat(login.tcp().receiveUntilClosed(WAIT)).containsExactly(new Bye("ended by user"));
        Thread.sleep(GRACE.toMillis());
        assertThat(screen.captures()).isZero();
        assertThat(server.lastUdpOutcome()).isEmpty();
    }

    @Test
    void endBeforeDecisionNeverStartsCapture() throws Exception {
        // The decision falls due soon after the end: a link left running would still start capture.
        startServer(UdpStartup.TIMEOUT);
        V3Login login = loginV3();

        ui.endSession();

        assertThat(login.tcp().receiveUntilClosed(WAIT)).containsExactly(new Bye("ended by user"));
        Thread.sleep(2500);
        assertThat(screen.captures()).isZero();
        assertThat(server.lastUdpOutcome()).isEmpty();
    }

    @Test
    void v2ViewerGetsNoUdpMessages() throws InterruptedException {
        startServer(SHORT_STARTUP);
        WireClient client = connect();

        client.send(new Hello(2, Hello.Role.VIEWER, ui.lastCode()));

        assertThat(client.receive(WAIT)).isEqualTo(new Hello(2, Hello.Role.AGENT, ""));
        assertThat(client.receive(WAIT)).isEqualTo(screen.info());
        assertThat(next(client)).isInstanceOf(TileUpdate.class);
    }

    @Test
    void udpBindFailureCapsAtV2() throws Exception {
        int free;
        try (ServerSocket probe = new ServerSocket(0)) {
            free = probe.getLocalPort();
        }
        try (DatagramSocket taken = new DatagramSocket(free)) {
            startServer(free, SHORT_STARTUP);

            assertThat(server.udpPort()).isEqualTo(-1);
            WireClient client = connect();
            client.send(new Hello(3, Hello.Role.VIEWER, ui.lastCode()));
            assertThat(client.receive(WAIT)).isEqualTo(new Hello(2, Hello.Role.AGENT, ""));
            assertThat(client.receive(WAIT)).isEqualTo(screen.info());
            assertThat(next(client)).isInstanceOf(TileUpdate.class);
        }
    }

    @Test
    void fixedPortBindsUdpOnTheSameNumber() throws Exception {
        int free;
        try (ServerSocket probe = new ServerSocket(0)) {
            free = probe.getLocalPort();
        }
        startServer(free, SHORT_STARTUP);

        assertThat(server.udpPort()).isEqualTo(free);
    }

    @Test
    void v2AgentOpensNoUdpPort() {
        AgentConfig config = new AgentConfig(0, Duration.ofSeconds(30), LONG, LONG, 30, 2);
        server = new AgentServer(config, screen, screen, new PairingCodes(clock, new Random(1)), ui);
        server.start();

        assertThat(server.udpPort()).isEqualTo(-1);
    }

    @Test
    void defaultAgentOpensNoUdpPort() {
        server = new AgentServer(AgentConfig.defaults(0), screen, screen, new PairingCodes(clock, new Random(1)), ui);
        server.start();

        assertThat(server.udpPort()).isEqualTo(-1);
    }

    @Test
    void deadUdpSocketCapsAtV2() throws Exception {
        startServer(SHORT_STARTUP);
        assertThat(server.udpPort()).isPositive();

        server.udpEndpoint().closeSocketUnexpectedly(); // as a fatal read error would

        assertThat(await(() -> server.udpPort() == -1)).isTrue();
        WireClient client = connect();
        client.send(new Hello(3, Hello.Role.VIEWER, ui.lastCode()));
        assertThat(client.receive(WAIT)).isEqualTo(new Hello(2, Hello.Role.AGENT, ""));
        assertThat(client.receive(WAIT)).isEqualTo(screen.info());
        assertThat(next(client)).isInstanceOf(TileUpdate.class);
    }

    @Test
    void sessionEndStopsProbes() throws Exception {
        startServer(SHORT_STARTUP);
        V3Login login = loginV3();
        UdpViewer v = udpViewer(login.setup());
        startProbing(v);

        login.tcp().send(new Bye("closed by viewer"));
        assertThat(login.tcp().awaitClosed(WAIT)).isTrue();
        Thread.sleep(GRACE.toMillis());
        v.drain();

        assertThat(v.collect(Duration.ofMillis(1500))).noneMatch(p -> p.type() == Type.PROBE);
    }

    @Test
    void secondSessionGetsNewKey() throws Exception {
        startServer(SHORT_STARTUP);
        String used = ui.lastCode();
        V3Login first = loginV3();
        first.tcp().send(new Bye("closed by viewer"));
        assertThat(first.tcp().awaitClosed(WAIT)).isTrue();
        awaitNewCode(used);

        V3Login second = loginV3();

        assertThat(second.setup().sessionId()).isNotEqualTo(first.setup().sessionId());
        assertThat(second.setup().key()).isNotEqualTo(first.setup().key());
        assertThat(second.setup().udpPort()).isEqualTo(first.setup().udpPort());
        assertThat(next(second.tcp())).isEqualTo(TCP);
    }

    @Test
    void failedStartReleasesTheUdpPort() throws Exception {
        int taken;
        try (ServerSocket tcp = new ServerSocket(0)) {
            taken = tcp.getLocalPort();
            AgentConfig config = new AgentConfig(taken, Duration.ofSeconds(30), LONG, LONG, 30, 3);
            AgentServer failing = new AgentServer(config, screen, screen, new PairingCodes(clock, new Random(1)), ui);

            assertThatThrownBy(failing::start).isNotNull();
            failing.close();
        }

        try (DatagramSocket again = new DatagramSocket(taken)) {
            assertThat(again.getLocalPort()).isEqualTo(taken);
        }
    }

    @Test
    void closeReleasesTheUdpPort() throws Exception {
        startServer(SHORT_STARTUP);
        loginV3();
        int udp = server.udpPort();

        server.close();
        server = null;

        try (DatagramSocket again = new DatagramSocket(udp)) {
            assertThat(again.getLocalPort()).isEqualTo(udp);
        }
    }

    /** A raw UDP "viewer" on loopback speaking the signed packet format with the key of a {@code UdpSetup}. */
    private static final class UdpViewer {
        private final UdpPacketCodec codec;
        private final DatagramSocket socket;
        private final InetSocketAddress agent;
        private final int sessionId;
        private long nextNumber = 1;

        UdpViewer(UdpSetup setup) throws IOException {
            codec = new UdpPacketCodec(setup.key());
            sessionId = setup.sessionId();
            socket = new DatagramSocket(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            agent = new InetSocketAddress(InetAddress.getLoopbackAddress(), setup.udpPort());
        }

        void send(Type type, byte[] payload) throws IOException {
            byte[] wire = codec.encode(new UdpPacket(type, Direction.VIEWER, sessionId, nextNumber++, payload));
            socket.send(new DatagramPacket(wire, wire.length, agent));
        }

        /** Next agent packet within {@code within}, or empty; undecodable datagrams are skipped. */
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
                Optional<UdpPacket> p = codec.decode(ByteBuffer.wrap(Arrays.copyOf(buf, dp.getLength())), sessionId,
                        Direction.AGENT);
                if (p.isPresent()) {
                    return p;
                }
            }
        }

        Optional<UdpPacket> receiveUntil(Predicate<UdpPacket> match, Duration within)
                throws IOException {
            long deadline = System.nanoTime() + within.toNanos();
            while (true) {
                Optional<UdpPacket> p = receive(Duration.ofNanos(Math.max(0, deadline - System.nanoTime())));
                if (p.isEmpty() || match.test(p.get())) {
                    return p;
                }
            }
        }

        /** Every agent packet arriving within {@code window}. */
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

        /** Discards what is already queued on the socket. */
        void drain() throws IOException {
            while (receive(Duration.ofMillis(20)).isPresent()) {
                // discard
            }
        }

        void close() {
            socket.close();
        }
    }
}

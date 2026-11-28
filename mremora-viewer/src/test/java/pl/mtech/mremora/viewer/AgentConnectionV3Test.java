package pl.mtech.mremora.viewer;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import pl.mtech.mremora.agent.AgentConfig;
import pl.mtech.mremora.agent.AgentServer;
import pl.mtech.mremora.agent.PairingCodes;
import pl.mtech.mremora.protocol.FrameUpdate;
import pl.mtech.mremora.protocol.ScreenInfo;
import pl.mtech.mremora.protocol.TileUpdate;
import pl.mtech.mremora.protocol.TransportState;
import pl.mtech.mremora.protocol.UdpSetup;
import pl.mtech.mremora.protocol.netty.LinkImpairment;
import pl.mtech.mremora.protocol.udp.CompleteRect;
import pl.mtech.mremora.testkit.MutableClock;

/** {@link AgentConnection} on protocol v3: the UDP channel beside the TCP session. */
class AgentConnectionV3Test {

    /** Generous upper bound for anything that should happen "now"; only reached when a test fails. */
    private static final Duration WAIT = Duration.ofSeconds(5);
    private static final TransportState TCP = new TransportState(TransportState.Mode.TCP, 0);
    private static final TransportState UDP = new TransportState(TransportState.Mode.UDP, 0);

    private final MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
    private final RecordingAgentUi ui = new RecordingAgentUi();
    private final FakeDesktop desktop = new FakeDesktop(32, 16);
    private final RecordingListener listener = new RecordingListener();
    private final List<AgentConnection> connections = new ArrayList<>();
    private AgentServer server;
    private FakeAgent fakeAgent;
    private int port;

    @AfterEach
    void tearDown() throws Exception {
        connections.forEach(AgentConnection::close);
        if (server != null) {
            server.close();
        }
        if (fakeAgent != null) {
            fakeAgent.close();
        }
    }

    private void startServer(int maxProtocolVersion) {
        AgentConfig defaults = AgentConfig.defaults(0);
        AgentConfig config = new AgentConfig(0, defaults.handshakeTimeout(), defaults.heartbeatInterval(),
                defaults.idleTimeout(), defaults.maxFps(), maxProtocolVersion);
        server = new AgentServer(config, desktop, desktop, new PairingCodes(clock, new Random(1)), ui);
        port = server.start();
    }

    private AgentConnection connect(int protocolVersion, LinkImpairment impairment) throws Exception {
        AgentConnection connection = AgentConnection.connect("127.0.0.1", port, ui.lastCode(), protocolVersion,
                listener, impairment).get(WAIT.toMillis(), TimeUnit.MILLISECONDS);
        connections.add(connection);
        return connection;
    }

    @Test
    void v3SessionEstablishesUdp() throws Exception {
        startServer(3);

        AgentConnection connection = connect(3, LinkImpairment.NONE);

        assertThat(connection.protocolVersion()).isEqualTo(3);
        assertThat(poll(() -> !listener.transportStates().isEmpty(), WAIT)).isTrue();
        // Plan 2: a working UDP channel carries the picture (rectangles over UDP, no TileUpdate over TCP).
        assertThat(listener.transportStates()).containsExactly(UDP);
        assertThat(connection.transportState()).isEqualTo(UDP);
        assertThat(poll(() -> listener.udpRects() >= 1, WAIT)).isTrue();
        assertThat(poll(() -> connection.udpLink() != null && connection.udpLink().established(), WAIT)).isTrue();
        assertThat(connection.udpLink().lastRttNanos()).isPresent();
        assertThat(listener.tiles()).as("no TileUpdate over TCP").isZero();
        assertThat(listener.closedReasons()).isEmpty();
    }

    @Test
    void persistentUdpReceiverFailureEndsTheSession() throws Exception {
        startServer(3);
        AgentConnection connection = connect(3, LinkImpairment.NONE);
        assertThat(poll(() -> connection.udpLink() != null && connection.udpLink().established(), WAIT)).isTrue();

        connection.udpLink().faults(new ViewerUdpLink.Faults() {
            @Override
            public void beforePacket() {
                throw new IllegalStateException("injected packet failure");
            }
        });

        // The agent's next packets (probes, probe replies, tiles) hit the failing receiver; the third ends the session.
        assertThat(poll(() -> !listener.closedReasons().isEmpty(), WAIT.multipliedBy(2))).isTrue();
        assertThat(listener.closedReasons()).containsExactly(AgentConnection.PROTOCOL_ERROR);
    }

    @Test
    void blockedUdpFallsBackAfterTimeout() throws Exception {
        startServer(3);
        long started = System.nanoTime();

        AgentConnection connection = connect(3, LinkImpairment.blocked());

        assertThat(poll(() -> !listener.transportStates().isEmpty(), WAIT)).isTrue();
        Duration waited = Duration.ofNanos(System.nanoTime() - started);
        assertThat(waited).isGreaterThanOrEqualTo(Duration.ofMillis(1800));
        assertThat(listener.transportStates()).containsExactly(TCP);
        assertThat(poll(() -> listener.tiles() >= 1, WAIT)).isTrue();
        assertThat(connection.udpLink()).isNotNull();
        assertThat(connection.udpLink().established()).isFalse();
    }

    @Test
    void realAgentAndRealViewerAgreeUdpWorks() throws Exception {
        startServer(3);

        connect(3, LinkImpairment.NONE);

        assertThat(poll(() -> !listener.transportStates().isEmpty(), WAIT)).isTrue();
        // UDP only when the agent accepted the viewer's PROBE_REPLY to an agent probe before the 2 s startup timeout;
        // the timeout itself decides TCP. (No wall-clock upper bound: the outcome already tells the two apart.)
        assertThat(poll(() -> server.lastUdpOutcome().isPresent(), WAIT)).isTrue();
        assertThat(server.lastUdpOutcome()).contains(TransportState.Mode.UDP);
        assertThat(listener.transportStates()).containsExactly(UDP); // plan 2: the picture goes over UDP
    }

    @Test
    void blockedViewerUdpMakesTheAgentDecideTcp() throws Exception {
        startServer(3);

        connect(3, LinkImpairment.blocked());

        assertThat(poll(() -> !listener.transportStates().isEmpty(), WAIT)).isTrue();
        assertThat(poll(() -> server.lastUdpOutcome().isPresent(), WAIT)).isTrue();
        assertThat(server.lastUdpOutcome()).contains(TransportState.Mode.TCP);
    }

    @Test
    void v3ViewerWithV2Agent() throws Exception {
        startServer(2);

        AgentConnection connection = connect(3, LinkImpairment.NONE);

        assertThat(connection.protocolVersion()).isEqualTo(2);
        assertThat(poll(() -> listener.tiles() >= 1, WAIT)).isTrue();
        assertThat(connection.udpLink()).isNull();
        assertThat(connection.transportState()).isNull();
        assertThat(listener.transportStates()).isEmpty();
    }

    @Test
    void v2ViewerWithV3Agent() throws Exception {
        startServer(3);

        AgentConnection connection = connect(2, LinkImpairment.NONE);

        assertThat(connection.protocolVersion()).isEqualTo(2);
        assertThat(poll(() -> listener.tiles() >= 1, WAIT)).isTrue();
        assertThat(connection.udpLink()).isNull();
        assertThat(listener.closedReasons()).isEmpty();
        connection.close();
        assertThat(listener.closedReasons()).containsExactly(AgentConnection.CLOSED_BY_VIEWER);
    }

    @Test
    void viewerCloseClosesTheUdpLink() throws Exception {
        startServer(3);
        AgentConnection connection = connect(3, LinkImpairment.NONE);
        assertThat(poll(() -> connection.udpLink() != null && connection.udpLink().established(), WAIT)).isTrue();

        connection.close();

        assertThat(connection.udpLink().closed()).isTrue();
    }

    @Test
    void agentByeClosesTheUdpLink() throws Exception {
        startServer(3);
        AgentConnection connection = connect(3, LinkImpairment.NONE);
        assertThat(poll(() -> connection.udpLink() != null, WAIT)).isTrue();
        assertThat(poll(ui::sessionShown, WAIT)).isTrue();

        ui.endSession();

        assertThat(poll(() -> !listener.closedReasons().isEmpty(), WAIT)).isTrue();
        assertThat(poll(() -> connection.udpLink().closed(), WAIT)).isTrue();
    }

    @Test
    void secondUdpSetupIsIgnored() throws Exception {
        try (DatagramSocket udpAgent = new DatagramSocket(
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 0))) {
            AgentConnection connection = connectToFake(3);
            byte[] key = new byte[UdpSetup.KEY_LENGTH];
            Arrays.fill(key, (byte) 5);
            fakeAgent.wire().send(new UdpSetup(udpAgent.getLocalPort(), 1, key));
            assertThat(poll(() -> connection.udpLink() != null, WAIT)).isTrue();
            ViewerUdpLink first = connection.udpLink();

            fakeAgent.wire().send(new UdpSetup(udpAgent.getLocalPort(), 2, key));
            fakeAgent.wire().send(TCP); // TCP is ordered: once this arrived, the second UdpSetup was handled

            assertThat(poll(() -> connection.transportState() != null, WAIT)).isTrue();
            assertThat(connection.udpLink()).isSameAs(first);
            assertThat(first.closed()).isFalse();
            assertThat(listener.closedReasons()).isEmpty();
        }
    }

    @Test
    void v2SessionIgnoresUdpMessages() throws Exception {
        AgentConnection connection = connectToFake(2);
        fakeAgent.wire().send(new UdpSetup(4000, 1, new byte[UdpSetup.KEY_LENGTH]));
        fakeAgent.wire().send(TCP);
        fakeAgent.wire().send(new TileUpdate(0, 32, 16, true, 0, List.of()));

        assertThat(poll(() -> listener.tiles() >= 1, WAIT)).isTrue();
        assertThat(connection.udpLink()).isNull();
        assertThat(connection.transportState()).isNull();
        assertThat(listener.transportStates()).isEmpty();
        assertThat(listener.closedReasons()).isEmpty();
    }

    /** Connects to a scripted agent answering with {@code version}. */
    private AgentConnection connectToFake(int version) throws Exception {
        fakeAgent = new FakeAgent();
        CompletableFuture<AgentConnection> future = AgentConnection.connect("127.0.0.1", fakeAgent.port(), "123456",
                3, listener, LinkImpairment.NONE);
        fakeAgent.handshake(version, new ScreenInfo(32, 16, 100), WAIT);
        AgentConnection connection = future.get(WAIT.toMillis(), TimeUnit.MILLISECONDS);
        connections.add(connection);
        return connection;
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

    /** Records tiles, transport states and close reasons; called on the connection's thread. */
    private static final class RecordingListener implements AgentConnection.Listener {

        private final AtomicInteger tiles = new AtomicInteger();
        private final AtomicInteger udpRects = new AtomicInteger();
        private final List<TransportState> transportStates = new ArrayList<>();
        private final List<String> closedReasons = new ArrayList<>();

        @Override
        public void onFrame(FrameUpdate frame) {
            throw new AssertionError("v1 frame");
        }

        @Override
        public void onTiles(TileUpdate part) {
            tiles.incrementAndGet();
        }

        @Override
        public void onUdpRect(CompleteRect rect) {
            udpRects.incrementAndGet();
        }

        @Override
        public synchronized void onTransportState(TransportState state) {
            transportStates.add(state);
        }

        @Override
        public synchronized void onClosed(String reason) {
            closedReasons.add(reason);
        }

        int tiles() {
            return tiles.get();
        }

        int udpRects() {
            return udpRects.get();
        }

        synchronized List<TransportState> transportStates() {
            return List.copyOf(transportStates);
        }

        synchronized List<String> closedReasons() {
            return List.copyOf(closedReasons);
        }
    }
}

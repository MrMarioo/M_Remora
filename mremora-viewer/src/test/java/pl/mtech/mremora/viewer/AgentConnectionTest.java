package pl.mtech.mremora.viewer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import pl.mtech.mremora.agent.AgentConfig;
import pl.mtech.mremora.agent.AgentServer;
import pl.mtech.mremora.agent.PairingCodes;
import pl.mtech.mremora.protocol.Bye;
import pl.mtech.mremora.protocol.FrameAck;
import pl.mtech.mremora.protocol.FrameUpdate;
import pl.mtech.mremora.protocol.Heartbeat;
import pl.mtech.mremora.protocol.Hello;
import pl.mtech.mremora.protocol.Message;
import pl.mtech.mremora.protocol.MouseButton;
import pl.mtech.mremora.protocol.MouseMove;
import pl.mtech.mremora.protocol.ScreenInfo;
import pl.mtech.mremora.protocol.StreamConfig;
import pl.mtech.mremora.protocol.TileUpdate;
import pl.mtech.mremora.testkit.MutableClock;

/** {@link AgentConnection} against a real {@link AgentServer} over loopback. */
class AgentConnectionTest {

    /** Generous upper bound for anything that should happen "now"; only reached when a test fails. */
    private static final Duration WAIT = Duration.ofSeconds(5);

    private final MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
    private final RecordingAgentUi ui = new RecordingAgentUi();
    private final FakeDesktop desktop = new FakeDesktop(32, 16);
    private final RecordingListener listener = new RecordingListener();
    private final List<AgentConnection> connections = new ArrayList<>();
    private AgentServer server;
    private int port;

    @BeforeEach
    void startServer() {
        server = new AgentServer(AgentConfig.defaults(0), desktop, desktop,
                new PairingCodes(clock, new Random(1)), ui);
        port = server.start();
    }

    /** Replaces the default agent with one offering at most {@code maxProtocolVersion}. */
    private void restartServer(int maxProtocolVersion) {
        server.close();
        AgentConfig defaults = AgentConfig.defaults(0);
        AgentConfig config = new AgentConfig(0, defaults.handshakeTimeout(), defaults.heartbeatInterval(),
                defaults.idleTimeout(), defaults.maxFps(), maxProtocolVersion);
        server = new AgentServer(config, desktop, desktop, new PairingCodes(clock, new Random(1)), ui);
        port = server.start();
    }

    @AfterEach
    void tearDown() {
        connections.forEach(AgentConnection::close);
        server.close();
    }

    private AgentConnection connect(String code) throws Exception {
        return connect(code, 1);
    }

    private AgentConnection connect(String code, int protocolVersion) throws Exception {
        AgentConnection connection = AgentConnection.connect("127.0.0.1", port, code, protocolVersion, listener)
                .get(WAIT.toMillis(), TimeUnit.MILLISECONDS);
        connections.add(connection);
        return connection;
    }

    /** Connects to {@code agent} and lets it answer with {@code version}. */
    private AgentConnection connectToFake(FakeAgent agent, int proposed, int version) throws Exception {
        CompletableFuture<AgentConnection> future =
                AgentConnection.connect("127.0.0.1", agent.port(), "123456", proposed, listener);
        agent.handshake(version, new ScreenInfo(32, 16, 100), WAIT);
        AgentConnection connection = future.get(WAIT.toMillis(), TimeUnit.MILLISECONDS);
        connections.add(connection);
        return connection;
    }

    private String wrongCode() {
        return ui.lastCode().equals("000000") ? "000001" : "000000";
    }

    @Test
    void connectsAndReceivesScreenInfoAndFrames() throws Exception {
        AgentConnection connection = connect(ui.lastCode());

        assertThat(connection.screenInfo()).isEqualTo(new ScreenInfo(32, 16, 100));
        assertThat(poll(() -> listener.frames() >= 3, Duration.ofSeconds(2))).isTrue();
    }

    @Test
    void defaultVersionIsTheWireVersionCappedAtWhatTheViewerImplements() {
        assertThat(AgentConnection.DEFAULT_PROTOCOL_VERSION)
                .isEqualTo(Math.min(Hello.PROTOCOL_VERSION, AgentConnection.HIGHEST_PROTOCOL_VERSION));
    }

    @Test
    void nullCodeIsRejectedUpFront() {
        assertThatThrownBy(() -> AgentConnection.connect("127.0.0.1", port, null, 1, listener))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("pairingCode");
    }

    @Test
    void codeWithSpacesIsAccepted() throws Exception {
        String code = ui.lastCode();

        AgentConnection connection = connect(code.substring(0, 3) + " " + code.substring(3));

        assertThat(connection.screenInfo()).isEqualTo(new ScreenInfo(32, 16, 100));
    }

    @Test
    void mouseReachesInjector() throws Exception {
        AgentConnection connection = connect(ui.lastCode());

        connection.sendInput(new MouseMove(7, 8));

        assertThat(poll(() -> desktop.calls().contains("move 7,8"), WAIT)).isTrue();
    }

    @Test
    void wrongCodeFailsWithReason() {
        CompletableFuture<AgentConnection> future = AgentConnection.connect("127.0.0.1", port, wrongCode(), 1, listener);

        assertThatThrownBy(() -> future.get(WAIT.toMillis(), TimeUnit.MILLISECONDS))
                .isInstanceOf(ExecutionException.class)
                .cause()
                .isInstanceOfSatisfying(AgentRejectedException.class,
                        e -> assertThat(e.reason()).isEqualTo("invalid pairing code"));
    }

    @Test
    void silentAgentTimesOut() throws Exception {
        // Accepts the TCP connection (backlog) but never answers.
        try (ServerSocket silent = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            CompletableFuture<AgentConnection> future =
                    AgentConnection.connect("127.0.0.1", silent.getLocalPort(), "123456", 1, listener);

            assertThatThrownBy(() -> future.get(15, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .cause()
                    .isInstanceOf(TimeoutException.class);
        }
    }

    @Test
    void endOnAgentClosesViewerWithReason() throws Exception {
        AgentConnection connection = connect(ui.lastCode());
        assertThat(poll(ui::sessionShown, WAIT)).isTrue();

        ui.endSession();

        assertThat(poll(() -> !listener.closedReasons().isEmpty(), WAIT)).isTrue();
        connection.close(); // waits for the connection's thread: no late second onClosed
        assertThat(listener.closedReasons()).containsExactly("ended by user");
    }

    @Test
    void viewerCloseReleasesHeldButton() throws Exception {
        AgentConnection connection = connect(ui.lastCode());
        connection.sendInput(new MouseButton(MouseButton.Button.LEFT, true));
        assertThat(poll(() -> desktop.calls().contains("press LEFT"), WAIT)).isTrue();

        connection.close();

        assertThat(poll(() -> desktop.calls().contains("release LEFT"), WAIT)).isTrue();
        assertThat(listener.closedReasons()).containsExactly("closed by viewer");
        connection.sendInput(new MouseMove(1, 1)); // ignored after close
    }

    @Test
    void rejectsNonInputMessage() throws Exception {
        AgentConnection connection = connect(ui.lastCode());

        assertThatThrownBy(() -> connection.sendInput(new Heartbeat()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void negotiatesV2WithANewAgent() throws Exception {
        restartServer(2);

        AgentConnection connection = connect(ui.lastCode(), 2);

        assertThat(connection.protocolVersion()).isEqualTo(2);
        // A static screen yields one frame and then nothing until it changes or the frame is acked.
        assertThat(poll(() -> listener.tiles() >= 1, WAIT)).isTrue();
        assertThat(listener.frames()).isZero();
    }

    @Test
    void defaultViewerAndAgentTalkV2() throws Exception {
        AgentConnection connection = AgentConnection.connect("127.0.0.1", port, ui.lastCode(), listener)
                .get(WAIT.toMillis(), TimeUnit.MILLISECONDS);
        connections.add(connection);

        assertThat(connection.protocolVersion()).isEqualTo(2);
        assertThat(poll(() -> listener.tiles() >= 1, WAIT)).isTrue();
        assertThat(listener.frames()).isZero();
    }

    @Test
    void negotiatesV1WithAnOldAgent() throws Exception {
        restartServer(1);

        AgentConnection connection = connect(ui.lastCode(), 2);

        assertThat(connection.protocolVersion()).isEqualTo(1);
        assertThat(poll(() -> listener.frames() >= 1, WAIT)).isTrue();
        assertThat(listener.tiles()).isZero();
    }

    @Test
    void v1SessionSendsNoAckNorStreamConfig() throws Exception {
        try (FakeAgent agent = new FakeAgent()) {
            AgentConnection connection = connectToFake(agent, 2, 1);

            connection.sendFrameAck(0);
            connection.sendStreamConfig(new StreamConfig(16, 8));
            connection.sendInput(new MouseMove(1, 2)); // sentinel: everything sent before it has arrived

            List<Message> received = new ArrayList<>();
            Message m;
            do {
                m = agent.wire().receive(WAIT);
                received.add(m);
            } while (!(m instanceof MouseMove));
            assertThat(received.subList(0, received.size() - 1)).allMatch(Heartbeat.class::isInstance);
            assertThat(m).isEqualTo(new MouseMove(1, 2));
        }
    }

    @Test
    void v2SessionSendsAckAndStreamConfig() throws Exception {
        try (FakeAgent agent = new FakeAgent()) {
            AgentConnection connection = connectToFake(agent, 2, 2);

            connection.sendFrameAck(7);
            connection.sendStreamConfig(new StreamConfig(16, 8));

            assertThat(receiveSkippingHeartbeats(agent)).isEqualTo(new FrameAck(7));
            assertThat(receiveSkippingHeartbeats(agent)).isEqualTo(new StreamConfig(16, 8));
        }
    }

    @Test
    void abortSaysByeWithTheReason() throws Exception {
        try (FakeAgent agent = new FakeAgent()) {
            AgentConnection connection = connectToFake(agent, 2, 2);

            connection.abort("protocol error");

            assertThat(agent.wire().receiveUntilClosed(WAIT))
                    .filteredOn(msg -> !(msg instanceof Heartbeat))
                    .containsExactly(new Bye("protocol error"));
            assertThat(poll(() -> !listener.closedReasons().isEmpty(), WAIT)).isTrue();
            connection.close(); // waits for the connection's thread: no late second onClosed
            assertThat(listener.closedReasons()).containsExactly("protocol error");
        }
    }

    private static Message receiveSkippingHeartbeats(FakeAgent agent) {
        while (true) {
            Message m = agent.wire().receive(WAIT);
            if (!(m instanceof Heartbeat)) {
                return m;
            }
        }
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

    /** Counts frames and records close reasons; called on the connection's thread. */
    private static final class RecordingListener implements AgentConnection.Listener {

        private final AtomicInteger frames = new AtomicInteger();
        private final AtomicInteger tiles = new AtomicInteger();
        private final List<String> closedReasons = new ArrayList<>();

        @Override
        public void onFrame(FrameUpdate frame) {
            frames.incrementAndGet();
        }

        @Override
        public void onTiles(TileUpdate part) {
            tiles.incrementAndGet();
        }

        @Override
        public synchronized void onClosed(String reason) {
            closedReasons.add(reason);
        }

        int frames() {
            return frames.get();
        }

        int tiles() {
            return tiles.get();
        }

        synchronized List<String> closedReasons() {
            return List.copyOf(closedReasons);
        }
    }
}

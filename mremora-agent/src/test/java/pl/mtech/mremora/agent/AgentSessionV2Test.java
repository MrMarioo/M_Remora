package pl.mtech.mremora.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.image.BufferedImage;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import pl.mtech.mremora.protocol.Bye;
import pl.mtech.mremora.protocol.FrameAck;
import pl.mtech.mremora.protocol.FrameUpdate;
import pl.mtech.mremora.protocol.Heartbeat;
import pl.mtech.mremora.protocol.Hello;
import pl.mtech.mremora.protocol.Message;
import pl.mtech.mremora.protocol.MouseButton;
import pl.mtech.mremora.protocol.StreamConfig;
import pl.mtech.mremora.protocol.TileUpdate;
import pl.mtech.mremora.testkit.MutableClock;
import pl.mtech.mremora.testkit.WireClient;

/** The v2 session over loopback: tile frames gated by {@code FrameAck}, resized by {@code StreamConfig}. */
class AgentSessionV2Test {

    /** Generous upper bound for anything the server should do "now"; only reached when a test fails. */
    private static final Duration WAIT = Duration.ofSeconds(5);
    private static final Duration LONG = Duration.ofSeconds(60);
    private static final String CAPTURE_THREAD = "mremora-capture";

    private final MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
    private final RecordingUi ui = new RecordingUi();
    private final List<WireClient> clients = new ArrayList<>();
    private FakeScreen screen = new FakeScreen(32, 16);
    private AgentServer server;
    private int port;

    @AfterEach
    void tearDown() {
        clients.forEach(WireClient::close);
        if (server != null) {
            server.close();
        }
    }

    /** Offers v2; long heartbeat and idle, so nothing but frames arrives unasked. */
    private void startServer() {
        AgentConfig config = new AgentConfig(0, Duration.ofSeconds(30), LONG, LONG, 30, 2);
        server = new AgentServer(config, screen, screen, new PairingCodes(clock, new Random(1)), ui);
        port = server.start();
    }

    /** Connects as a viewer speaking {@code version} and reads the agent's handshake reply. */
    private WireClient authenticate(int version) throws InterruptedException {
        WireClient client = WireClient.connect(port);
        clients.add(client);
        client.send(new Hello(version, Hello.Role.VIEWER, ui.lastCode()));
        assertThat(client.receive(WAIT)).isEqualTo(new Hello(version, Hello.Role.AGENT, ""));
        assertThat(client.receive(WAIT)).isEqualTo(screen.info());
        assertThat(ui.awaitCall("session ", WAIT)).isTrue();
        return client;
    }

    /** Reads the parts of one frame up to {@code lastPart}; they all share one sequence. */
    private static List<TileUpdate> receiveFrame(WireClient client) {
        List<TileUpdate> parts = new ArrayList<>();
        while (parts.isEmpty() || !parts.getLast().lastPart()) {
            Message m = client.receive(WAIT);
            if (m instanceof Heartbeat) {
                continue;
            }
            assertThat(m).isInstanceOf(TileUpdate.class);
            parts.add((TileUpdate) m);
        }
        assertThat(parts).extracting(TileUpdate::sequence).containsOnly(parts.getFirst().sequence());
        return parts;
    }

    /** Asserts that nothing but heartbeats arrives within {@code silence}; a closed connection fails the test. */
    private static void expectSilence(WireClient client, Duration silence) {
        long deadline = System.nanoTime() + silence.toNanos();
        while (true) {
            long left = deadline - System.nanoTime();
            if (left <= 0) {
                return;
            }
            Message m;
            try {
                m = client.receive(Duration.ofNanos(left));
            } catch (AssertionError e) {
                if (String.valueOf(e.getMessage()).startsWith("No message within")) {
                    return;
                }
                throw e;
            }
            assertThat(m).as("expected silence").isInstanceOf(Heartbeat.class);
        }
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

    private static Set<Thread> captureThreads() {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(t -> t.getName().equals(CAPTURE_THREAD) && t.isAlive())
                .collect(Collectors.toSet());
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

    @Test
    void v2ViewerGetsTileUpdatesNotFrameUpdates() throws InterruptedException {
        startServer();
        WireClient viewer = authenticate(2);

        List<TileUpdate> frame = receiveFrame(viewer);

        assertThat(frame.getFirst().sequence()).isZero();
        assertThat(frame).allSatisfy(p -> {
            assertThat(p.streamWidth()).isEqualTo(32);
            assertThat(p.streamHeight()).isEqualTo(16);
        });
    }

    @Test
    void noSecondFrameBeforeTheAck() throws InterruptedException {
        startServer();
        WireClient viewer = authenticate(2);
        long first = receiveFrame(viewer).getFirst().sequence();

        screen.setImage(filled(32, 16, 0x336699));
        expectSilence(viewer, Duration.ofMillis(500));
        viewer.send(new FrameAck(first));

        assertThat(receiveFrame(viewer).getFirst().sequence()).isEqualTo(first + 1);
    }

    @Test
    void staticScreenSendsNothingAfterTheFirstFrame() throws InterruptedException {
        startServer();
        WireClient viewer = authenticate(2);
        long first = receiveFrame(viewer).getFirst().sequence();

        viewer.send(new FrameAck(first));

        expectSilence(viewer, Duration.ofSeconds(1));
    }

    @Test
    void staleAckDoesNotReleaseAFrame() throws InterruptedException {
        startServer();
        WireClient viewer = authenticate(2);
        long first = receiveFrame(viewer).getFirst().sequence();
        screen.setImage(filled(32, 16, 0x336699));

        viewer.send(new FrameAck(first + 7));

        expectSilence(viewer, Duration.ofMillis(500));
        // Still gated on the right ack, not stuck: the real one releases the waiting change.
        viewer.send(new FrameAck(first));
        assertThat(receiveFrame(viewer).getFirst().sequence()).isEqualTo(first + 1);
    }

    @Test
    void duplicateAckDoesNotAllowTwoFramesInFlight() throws InterruptedException {
        startServer();
        WireClient viewer = authenticate(2);
        long first = receiveFrame(viewer).getFirst().sequence();
        screen.setImage(filled(32, 16, 0x336699));
        viewer.send(new FrameAck(first));
        long second = receiveFrame(viewer).getFirst().sequence();
        screen.setImage(filled(32, 16, 0x993366));

        viewer.send(new FrameAck(first));

        expectSilence(viewer, Duration.ofMillis(500));
        viewer.send(new FrameAck(second));
        assertThat(receiveFrame(viewer).getFirst().sequence()).isEqualTo(second + 1);
    }

    @Test
    void streamConfigWhileUnackedGivesAFullFrameInTheNewSize() throws InterruptedException {
        screen = new FakeScreen(1280, 720);
        startServer();
        WireClient viewer = authenticate(2);
        long first = receiveFrame(viewer).getFirst().sequence();

        viewer.send(new StreamConfig(640, 360));
        viewer.send(new FrameAck(first));

        List<TileUpdate> frame = receiveFrame(viewer);
        assertThat(frame.getFirst().sequence()).isEqualTo(first + 1);
        boolean[] covered = new boolean[640 * 360];
        for (TileUpdate part : frame) {
            assertThat(part.streamWidth()).isEqualTo(640);
            assertThat(part.streamHeight()).isEqualTo(360);
            for (TileUpdate.Rect r : part.rects()) {
                assertThat(r.x() + r.width()).isLessThanOrEqualTo(640);
                assertThat(r.y() + r.height()).isLessThanOrEqualTo(360);
                for (int y = r.y(); y < r.y() + r.height(); y++) {
                    for (int x = r.x(); x < r.x() + r.width(); x++) {
                        covered[y * 640 + x] = true;
                    }
                }
            }
        }
        for (int i = 0; i < covered.length; i++) {
            assertThat(covered[i]).as("pixel %d,%d covered", i % 640, i / 640).isTrue();
        }
    }

    @Test
    void endWhileCaptureWaitsOnTheGateStopsCleanly() throws InterruptedException {
        startServer();
        String usedCode = ui.lastCode();
        Set<Thread> before = captureThreads();
        WireClient viewer = authenticate(2);
        receiveFrame(viewer);
        Set<Thread> ours = captureThreads();
        ours.removeAll(before);
        assertThat(ours).hasSize(1);
        Thread capture = ours.iterator().next();
        viewer.send(new MouseButton(MouseButton.Button.LEFT, true));
        assertThat(await(() -> screen.calls().contains("press LEFT"))).isTrue();
        // Parked on the gate (the interval sleep would be TIMED_WAITING).
        assertThat(await(() -> capture.getState() == Thread.State.WAITING)).isTrue();

        viewer.send(new Bye("closed by viewer"));

        capture.join(2000);
        assertThat(capture.isAlive()).isFalse();
        assertThat(await(() -> screen.calls().contains("release LEFT"))).isTrue();
        assertThat(await(() -> ui.calls().getLast().startsWith("code ")
                && !ui.calls().getLast().equals("code " + usedCode))).isTrue();
    }

    @Test
    void v1ViewerStillGetsFrameUpdates() throws InterruptedException {
        startServer();
        WireClient viewer = authenticate(1);

        assertThat(viewer.receive(WAIT)).isInstanceOf(FrameUpdate.class);
    }

    @Test
    void streamConfigOnV1IsAProtocolError() throws InterruptedException {
        startServer();
        WireClient viewer = authenticate(1);

        viewer.send(new StreamConfig(640, 360));

        List<Message> messages = viewer.receiveUntilClosed(WAIT);
        assertThat(messages.getLast()).isEqualTo(new Bye("protocol error"));
        assertThat(messages.subList(0, messages.size() - 1)).allMatch(FrameUpdate.class::isInstance);
    }

    @Test
    void frameAckOnV1IsAProtocolError() throws InterruptedException {
        startServer();
        WireClient viewer = authenticate(1);

        viewer.send(new FrameAck(0));

        List<Message> messages = viewer.receiveUntilClosed(WAIT);
        assertThat(messages.getLast()).isEqualTo(new Bye("protocol error"));
        assertThat(messages.subList(0, messages.size() - 1)).allMatch(FrameUpdate.class::isInstance);
    }

    @Test
    void oldViewerHelloV1NeverGetsTileUpdate() throws InterruptedException {
        server = new AgentServer(AgentConfig.defaults(0), screen, screen, new PairingCodes(clock, new Random(1)), ui);
        port = server.start();
        WireClient viewer = authenticate(1);

        // v1 streams every capture, so frames keep coming; every message up to the third frame is checked.
        int frames = 0;
        while (frames < 3) {
            Message m = viewer.receive(WAIT);
            assertThat(m).isInstanceOfAny(FrameUpdate.class, Heartbeat.class);
            if (m instanceof FrameUpdate) {
                frames++;
            }
        }
        assertThat(frames).isGreaterThanOrEqualTo(1);
    }

    @Test
    void defaultsOfferTheWireVersionCappedAtWhatTheAgentImplements() {
        assertThat(AgentConfig.DEFAULT_PROTOCOL_VERSION)
                .isEqualTo(Math.min(Hello.PROTOCOL_VERSION, AgentConfig.HIGHEST_PROTOCOL_VERSION));
        assertThat(AgentConfig.defaults(0).maxProtocolVersion()).isEqualTo(AgentConfig.DEFAULT_PROTOCOL_VERSION);
    }

    @Test
    void defaultAgentAnswersANewerViewerWithItsOwnHighestVersion() throws InterruptedException {
        server = new AgentServer(AgentConfig.defaults(0), screen, screen, new PairingCodes(clock, new Random(1)), ui);
        port = server.start();
        WireClient viewer = WireClient.connect(port);
        clients.add(viewer);

        viewer.send(new Hello(3, Hello.Role.VIEWER, ui.lastCode()));

        assertThat(viewer.receive(WAIT)).isEqualTo(new Hello(2, Hello.Role.AGENT, ""));
        assertThat(viewer.receive(WAIT)).isEqualTo(screen.info());
        assertThat(receiveFrame(viewer)).isNotEmpty();
    }

    @Test
    void defaultAgentSpeaksV2WithAHelloCarryingTheCurrentVersion() throws InterruptedException {
        server = new AgentServer(AgentConfig.defaults(0), screen, screen, new PairingCodes(clock, new Random(1)), ui);
        port = server.start();
        assertThat(Hello.PROTOCOL_VERSION).isEqualTo(2);
        WireClient viewer = authenticate(Hello.PROTOCOL_VERSION);

        assertThat(receiveFrame(viewer)).isNotEmpty();
    }
}

package pl.mtech.mremora.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.image.BufferedImage;
import java.time.Duration;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import pl.mtech.mremora.protocol.Bye;
import pl.mtech.mremora.protocol.FrameUpdate;
import pl.mtech.mremora.protocol.Heartbeat;
import pl.mtech.mremora.protocol.Hello;
import pl.mtech.mremora.protocol.Message;
import pl.mtech.mremora.protocol.MouseButton;
import pl.mtech.mremora.protocol.MouseMove;
import pl.mtech.mremora.protocol.MouseWheel;
import pl.mtech.mremora.protocol.ScreenInfo;
import pl.mtech.mremora.testkit.MutableClock;
import pl.mtech.mremora.testkit.WireClient;

class AgentSessionTest {

    /** Generous upper bound for anything the server should do "now"; only reached when a test fails. */
    private static final Duration WAIT = Duration.ofSeconds(5);
    private static final Duration LONG = Duration.ofSeconds(60);

    private final MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
    private final RecordingUi ui = new RecordingUi();
    private final List<WireClient> clients = new ArrayList<>();
    private FakeScreen screen = new FakeScreen(32, 16);
    private AgentServer server;
    private int port;

    @AfterEach
    void tearDown() {
        screen.openMoveGate();
        clients.forEach(WireClient::close);
        if (server != null) {
            server.close();
        }
    }

    /** Long heartbeat and idle by default: no heartbeat or idle close interferes with the expected messages. */
    private void startServer() {
        startServer(LONG);
    }

    private void startServer(Duration idleTimeout) {
        AgentConfig config = new AgentConfig(0, Duration.ofSeconds(30), LONG, idleTimeout, 30);
        server = new AgentServer(config, screen, screen, new PairingCodes(clock, new Random(1)), ui);
        port = server.start();
    }

    /** Connects with the code currently shown and reads the agent's handshake reply. */
    private WireClient authenticate() throws InterruptedException {
        WireClient client = WireClient.connect(port);
        clients.add(client);
        client.send(new Hello(1, Hello.Role.VIEWER, ui.lastCode()));
        assertThat(client.receive(WAIT)).isEqualTo(new Hello(1, Hello.Role.AGENT, ""));
        assertThat(client.receive(WAIT)).isEqualTo(screen.info());
        assertThat(ui.awaitCall("session ", WAIT)).isTrue();
        return client;
    }

    /** Reads until the server closes; asserts the stream is frames, then exactly one {@code Bye(reason)}. */
    private static void expectFramesThenBye(WireClient client, String reason) {
        List<Message> messages = client.receiveUntilClosed(WAIT);
        assertThat(messages).isNotEmpty();
        assertThat(messages.getLast()).isEqualTo(new Bye(reason));
        assertThat(messages.subList(0, messages.size() - 1)).allMatch(FrameUpdate.class::isInstance);
    }

    /** Runs {@code body} with {@code logged}'s logger off: keeps an expected ERROR log out of the test output. */
    private static void withLogOff(Class<?> logged, ThrowingRunnable body) throws InterruptedException {
        java.util.logging.Logger log = java.util.logging.Logger.getLogger(logged.getName());
        java.util.logging.Level previous = log.getLevel();
        log.setLevel(java.util.logging.Level.OFF);
        try {
            body.run();
        } finally {
            log.setLevel(previous);
        }
    }

    private interface ThrowingRunnable {
        void run() throws InterruptedException;
    }

    /**
     * Detects the server's close without reading (reading would let a stuck write finish): sends heartbeats until
     * the connection is reset.
     */
    private static boolean awaitResetBySending(WireClient client, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            try {
                client.send(new Heartbeat());
            } catch (UncheckedIOException e) {
                return true;
            }
            Thread.sleep(100);
        }
        return false;
    }

    private static BufferedImage noise(int width, int height) {
        var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        var random = new Random(42);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.setRGB(x, y, random.nextInt(0x1000000));
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

    /** UI calls recorded after the latest {@code session} call. */
    private List<String> callsAfterSession() {
        List<String> calls = ui.calls();
        int last = -1;
        for (int i = 0; i < calls.size(); i++) {
            if (calls.get(i).startsWith("session ")) {
                last = i;
            }
        }
        return calls.subList(last + 1, calls.size());
    }

    private boolean awaitNewCodeShown(String usedCode) throws InterruptedException {
        return await(() -> {
            List<String> after = callsAfterSession();
            return !after.isEmpty() && after.getLast().startsWith("code ")
                    && !after.getLast().equals("code " + usedCode);
        });
    }

    @Test
    void framesArriveWithConsecutiveSequence() throws InterruptedException {
        startServer();
        WireClient viewer = authenticate();

        for (long expected = 0; expected < 3; expected++) {
            Message m = viewer.receive(WAIT);
            assertThat(m).isInstanceOf(FrameUpdate.class);
            FrameUpdate frame = (FrameUpdate) m;
            assertThat(frame.sequence()).isEqualTo(expected);
            assertThat(frame.format()).isEqualTo(FrameUpdate.Format.JPEG);
            assertThat(frame.dataLength()).isPositive();
        }
    }

    @Test
    void mouseMessagesReachInjectorInOrder() throws InterruptedException {
        startServer();
        WireClient viewer = authenticate();

        viewer.send(new MouseMove(5, 6));
        viewer.send(new MouseButton(MouseButton.Button.LEFT, true));
        viewer.send(new MouseButton(MouseButton.Button.LEFT, false));
        viewer.send(new MouseWheel(2));

        List<String> expected = List.of("move 5,6", "press LEFT", "release LEFT", "wheel 2");
        assertThat(await(() -> screen.calls().size() >= expected.size())).isTrue();
        assertThat(screen.calls()).isEqualTo(expected);
    }

    @Test
    void secondHelloEndsSessionWithProtocolError() throws InterruptedException {
        startServer();
        WireClient viewer = authenticate();

        viewer.send(new Hello(1, Hello.Role.VIEWER, "123456"));

        expectFramesThenBye(viewer, "protocol error");
    }

    @Test
    void userEndSendsByeReleasesButtonsAndShowsNewCode() throws InterruptedException {
        startServer();
        String usedCode = ui.lastCode();
        WireClient viewer = authenticate();
        viewer.send(new MouseButton(MouseButton.Button.LEFT, true));
        assertThat(await(() -> screen.calls().contains("press LEFT"))).isTrue();

        ui.endSession();

        expectFramesThenBye(viewer, "ended by user");
        assertThat(await(() -> screen.calls().contains("release LEFT"))).isTrue();
        assertThat(awaitNewCodeShown(usedCode)).isTrue();
        assertThat(ui.calls().getLast()).startsWith("code ").isNotEqualTo("code " + usedCode);
    }

    @Test
    void viewerDisconnectMidDragReleasesButton() throws InterruptedException {
        startServer();
        String usedCode = ui.lastCode();
        WireClient viewer = authenticate();
        viewer.send(new MouseButton(MouseButton.Button.RIGHT, true));
        assertThat(await(() -> screen.calls().contains("press RIGHT"))).isTrue();

        viewer.close();

        // The brief's bound is 1 s; polled with the generous WAIT so a slow CI machine cannot flake.
        assertThat(await(() -> screen.calls().contains("release RIGHT"))).isTrue();
        assertThat(awaitNewCodeShown(usedCode)).isTrue();
        authenticate();
    }

    @Test
    void viewerByeEndsSessionWithoutReply() throws InterruptedException {
        startServer();
        String usedCode = ui.lastCode();
        WireClient viewer = authenticate();

        viewer.send(new Bye("closed by viewer"));

        assertThat(viewer.receiveUntilClosed(WAIT)).allMatch(FrameUpdate.class::isInstance);
        assertThat(awaitNewCodeShown(usedCode)).isTrue();
    }

    @Test
    void captureFailureEndsSessionWithAgentError() throws InterruptedException {
        screen.failFromCapture(3);
        withLogOff(AgentSession.class, () -> {
            startServer();
            String usedCode = ui.lastCode();
            WireClient viewer = authenticate();

            expectFramesThenBye(viewer, "agent error");
            assertThat(awaitNewCodeShown(usedCode)).isTrue();
        });
    }

    @Test
    void silentViewerTimesOut() throws InterruptedException {
        startServer(Duration.ofMillis(300));
        WireClient viewer = authenticate();

        expectFramesThenBye(viewer, "timeout");
    }

    @Test
    void endDuringFrameWriteEndsOnce() throws InterruptedException {
        screen = new FakeScreen(noise(1920, 1080));
        startServer();
        String usedCode = ui.lastCode();
        WireClient viewer = authenticate();

        List<Thread> enders = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            enders.add(Thread.ofPlatform().start(ui::endSession));
        }
        for (Thread t : enders) {
            t.join(WAIT.toMillis());
        }

        List<Message> messages = viewer.receiveUntilClosed(WAIT);
        assertThat(messages.stream().filter(Bye.class::isInstance)).containsExactly(new Bye("ended by user"));
        assertThat(awaitNewCodeShown(usedCode)).isTrue();
        Thread.sleep(1500); // past one UI refresh tick: a duplicate showCode would show up here
        assertThat(callsAfterSession()).hasSize(1);
    }

    @Test
    void slowViewerBoundsCaptures() throws InterruptedException {
        screen = new FakeScreen(noise(1920, 1080));
        startServer();
        WireClient viewer = authenticate();

        viewer.pauseReading();
        Thread.sleep(2000);

        assertThat(screen.captures()).isBetween(1, 19);
    }

    @Test
    void frozenViewerTimesOutAndReleasesButton() throws InterruptedException {
        screen = new FakeScreen(noise(1920, 1080));
        startServer(Duration.ofSeconds(1));
        String usedCode = ui.lastCode();
        WireClient viewer = authenticate();
        viewer.send(new MouseButton(MouseButton.Button.RIGHT, true));
        assertThat(await(() -> screen.calls().contains("press RIGHT"))).isTrue();

        viewer.pauseReading(); // a frame stays in flight, so a Bye from the agent can never be flushed

        assertThat(await(() -> screen.calls().contains("release RIGHT"))).isTrue();
        assertThat(awaitNewCodeShown(usedCode)).isTrue();
    }

    @Test
    void closeWithPendingConnectionSaysShuttingDown() throws InterruptedException {
        startServer();
        WireClient pending = WireClient.connect(port);
        clients.add(pending);
        // The connection is admitted asynchronously; a busy probe proves the pending slot is taken.
        WireClient probe = WireClient.connect(port);
        clients.add(probe);
        assertThat(probe.receive(WAIT)).isEqualTo(new Bye("busy"));

        server.close();

        assertThat(pending.receiveUntilClosed(WAIT)).containsExactly(new Bye("agent shutting down"));
    }

    @Test
    void closeDuringSessionReleasesButtonsAndSaysShuttingDown() throws InterruptedException {
        startServer();
        WireClient viewer = authenticate();
        viewer.send(new MouseButton(MouseButton.Button.LEFT, true));
        assertThat(await(() -> screen.calls().contains("press LEFT"))).isTrue();

        server.close();

        assertThat(screen.calls()).contains("release LEFT");
        expectFramesThenBye(viewer, "agent shutting down");
    }

    @Test
    void inputQueuedBeforeEndIsNotInjectedAfterIt() throws InterruptedException {
        startServer();
        WireClient viewer = authenticate();
        viewer.send(new MouseButton(MouseButton.Button.LEFT, true));
        assertThat(await(() -> screen.calls().contains("press LEFT"))).isTrue();
        screen.closeMoveGate();
        viewer.send(new MouseMove(1, 1));
        assertThat(screen.awaitMoveBlocked(WAIT)).isTrue();

        for (int i = 2; i <= 4; i++) {
            viewer.send(new MouseMove(i, i));
        }
        // Same channel, after the moves: they are read and queued before this ends the session.
        viewer.send(new Hello(1, Hello.Role.VIEWER, "123456"));
        expectFramesThenBye(viewer, "protocol error");
        screen.openMoveGate();

        assertThat(await(() -> screen.calls().contains("release LEFT"))).isTrue();
        assertThat(screen.calls()).containsExactly("press LEFT", "move 1,1", "release LEFT");
    }

    @Test
    void endClosesConnectionEvenWhenByeCannotBeFlushed() throws InterruptedException {
        screen = new FakeScreen(noise(1920, 1080));
        startServer();
        WireClient viewer = authenticate();
        viewer.pauseReading();
        Thread.sleep(2000); // frames fill the TCP buffers, so one stays in flight and the Bye queues behind it

        ui.endSession();

        // Generous bound for a forced close after ~2 s; reached only when the connection is never closed.
        assertThat(awaitResetBySending(viewer, Duration.ofSeconds(15))).isTrue();
    }

    @Test
    void failingSessionIndicatorEndsSessionWithAgentError() throws InterruptedException {
        ui.failShowSession();
        withLogOff(AgentServer.class, () -> {
            startServer();
            String usedCode = ui.lastCode();
            WireClient viewer = WireClient.connect(port);
            clients.add(viewer);
            viewer.send(new Hello(1, Hello.Role.VIEWER, usedCode));
            assertThat(viewer.receive(WAIT)).isEqualTo(new Hello(1, Hello.Role.AGENT, ""));
            assertThat(viewer.receive(WAIT)).isEqualTo(screen.info());

            expectFramesThenBye(viewer, "agent error");
            assertThat(awaitNewCodeShown(usedCode)).isTrue();
        });
    }

    @Test
    void concurrentClosesBothReturnAfterReleasingButtonsOnce() throws InterruptedException {
        startServer();
        WireClient viewer = authenticate();
        viewer.send(new MouseButton(MouseButton.Button.LEFT, true));
        assertThat(await(() -> screen.calls().contains("press LEFT"))).isTrue();

        // Like the quit thread and a Ctrl+C shutdown hook racing each other.
        Thread first = Thread.ofPlatform().start(server::close);
        Thread second = Thread.ofPlatform().start(server::close);
        first.join(WAIT.multipliedBy(3).toMillis());
        second.join(WAIT.multipliedBy(3).toMillis());

        assertThat(first.isAlive()).isFalse();
        assertThat(second.isAlive()).isFalse();
        assertThat(screen.calls()).containsOnlyOnce("release LEFT");
        expectFramesThenBye(viewer, "agent shutting down");
    }
}

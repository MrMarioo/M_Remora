package pl.mtech.mremora.viewer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import pl.mtech.mremora.agent.AgentConfig;
import pl.mtech.mremora.agent.AgentServer;
import pl.mtech.mremora.agent.PairingCodes;
import pl.mtech.mremora.protocol.MouseButton;
import pl.mtech.mremora.protocol.ScreenInfo;
import pl.mtech.mremora.testkit.MutableClock;

/** {@link ViewerSession} against a real {@link AgentServer} over loopback, with an inline "UI" executor. */
class ViewerSessionTest {

    /** Generous upper bound for anything that should happen "now"; only reached when a test fails. */
    private static final Duration WAIT = Duration.ofSeconds(5);
    private static final Executor UI = Runnable::run;

    private final MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
    private final RecordingAgentUi ui = new RecordingAgentUi();
    private final FakeDesktop desktop = new FakeDesktop(32, 16);
    private final RecordingListener listener = new RecordingListener();
    private final List<ViewerSession> sessions = new ArrayList<>();
    private AgentServer server;
    private int port;

    @BeforeEach
    void startServer() {
        server = new AgentServer(AgentConfig.defaults(0), desktop, desktop,
                new PairingCodes(clock, new Random(1)), ui);
        port = server.start();
    }

    @AfterEach
    void tearDown() throws Exception {
        try {
            for (ViewerSession session : sessions) {
                session.disconnect().get(WAIT.toMillis(), TimeUnit.MILLISECONDS);
            }
        } finally {
            server.close();
        }
    }

    private ViewerSession open() throws Exception {
        ViewerSession session = ViewerSession.open("127.0.0.1", port, ui.lastCode(), UI, listener)
                .get(WAIT.toMillis(), TimeUnit.MILLISECONDS);
        sessions.add(session);
        return session;
    }

    @Test
    void framesArriveDecoded() throws Exception {
        ViewerSession session = open();

        assertThat(session.screenInfo()).isEqualTo(new ScreenInfo(32, 16, 100));
        assertThat(poll(() -> !listener.frames().isEmpty(), WAIT)).isTrue();
        DecodedFrame frame = listener.frames().getFirst();
        assertThat(frame.width()).isEqualTo(32);
        assertThat(frame.height()).isEqualTo(16);
        assertThat(frame.argb()).hasSize(32 * 16);
    }

    @Test
    void clickInScaledViewReachesAgentPixels() throws Exception {
        ViewerSession session = open();

        session.input().setViewport(Viewport.fit(32, 16, 64, 32));
        session.input().pressed(MouseButton.Button.LEFT, 20, 10);
        session.input().released(MouseButton.Button.LEFT, 20, 10);

        assertThat(poll(() -> desktop.calls().contains("release LEFT"), WAIT)).isTrue();
        assertThat(desktop.calls()).containsSubsequence("move 10,5", "press LEFT", "release LEFT");
    }

    @Test
    void disconnectReleasesHeldButtonAndReportsOnce() throws Exception {
        ViewerSession session = open();
        session.input().setViewport(Viewport.fit(32, 16, 64, 32));
        session.input().pressed(MouseButton.Button.LEFT, 20, 10);
        // The agent drops input still queued when the session ends, so let the press land first.
        assertThat(poll(() -> desktop.calls().contains("press LEFT"), WAIT)).isTrue();

        CompletableFuture<Void> disconnected = session.disconnect();

        assertThat(session.disconnect()).isSameAs(disconnected);
        disconnected.get(WAIT.toMillis(), TimeUnit.MILLISECONDS);
        assertThat(poll(() -> desktop.calls().contains("release LEFT"), WAIT)).isTrue();
        assertThat(desktop.calls()).containsSubsequence("press LEFT", "release LEFT");
        assertThat(poll(() -> !listener.closedReasons().isEmpty(), WAIT)).isTrue();
        session.disconnect().get(WAIT.toMillis(), TimeUnit.MILLISECONDS);
        assertThat(listener.closedReasons()).containsExactly("closed by viewer");
    }

    @Test
    void agentEndingSessionIsReported() throws Exception {
        ViewerSession session = open();
        assertThat(poll(ui::sessionShown, WAIT)).isTrue();

        ui.endSession();

        assertThat(poll(() -> !listener.closedReasons().isEmpty(), WAIT)).isTrue();
        session.disconnect().get(WAIT.toMillis(), TimeUnit.MILLISECONDS); // no late second onClosed
        assertThat(listener.closedReasons()).containsExactly("ended by user");
    }

    @Test
    void wrongCodeFailsWithRejection() {
        String wrongCode = ui.lastCode().equals("000000") ? "000001" : "000000";

        CompletableFuture<ViewerSession> future = ViewerSession.open("127.0.0.1", port, wrongCode, UI, listener);

        assertThatThrownBy(() -> future.get(WAIT.toMillis(), TimeUnit.MILLISECONDS))
                .isInstanceOf(ExecutionException.class)
                .cause()
                .isInstanceOfSatisfying(AgentRejectedException.class,
                        e -> assertThat(e.reason()).isEqualTo("invalid pairing code"));
        assertThat(listener.frames()).isEmpty();
        assertThat(listener.closedReasons()).isEmpty();
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

    /** Records frames and close reasons; called on the decoder and connection threads. */
    private static final class RecordingListener implements ViewerSession.Listener {

        private final List<DecodedFrame> frames = new ArrayList<>();
        private final List<String> closedReasons = new ArrayList<>();

        @Override
        public synchronized void onFrame(DecodedFrame frame) {
            frames.add(frame);
        }

        @Override
        public synchronized void onClosed(String reason) {
            closedReasons.add(reason);
        }

        synchronized List<DecodedFrame> frames() {
            return List.copyOf(frames);
        }

        synchronized List<String> closedReasons() {
            return List.copyOf(closedReasons);
        }
    }
}

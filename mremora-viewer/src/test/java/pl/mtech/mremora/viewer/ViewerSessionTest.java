package pl.mtech.mremora.viewer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import pl.mtech.mremora.agent.AgentConfig;
import pl.mtech.mremora.agent.AgentServer;
import pl.mtech.mremora.agent.PairingCodes;
import pl.mtech.mremora.protocol.Bye;
import pl.mtech.mremora.protocol.FrameAck;
import pl.mtech.mremora.protocol.Heartbeat;
import pl.mtech.mremora.protocol.Message;
import pl.mtech.mremora.protocol.MouseButton;
import pl.mtech.mremora.protocol.MouseMove;
import pl.mtech.mremora.protocol.RectPixels;
import pl.mtech.mremora.protocol.ScreenInfo;
import pl.mtech.mremora.protocol.StreamConfig;
import pl.mtech.mremora.protocol.TileUpdate;
import pl.mtech.mremora.protocol.TransportState;
import pl.mtech.mremora.protocol.udp.UdpImageSender;
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

    /** Replaces the default agent with one offering at most {@code maxProtocolVersion}, showing {@code screen}. */
    private void restartServer(int maxProtocolVersion, FakeDesktop screen) {
        server.close();
        AgentConfig defaults = AgentConfig.defaults(0);
        AgentConfig config = new AgentConfig(0, defaults.handshakeTimeout(), defaults.heartbeatInterval(),
                defaults.idleTimeout(), defaults.maxFps(), maxProtocolVersion);
        server = new AgentServer(config, screen, screen, new PairingCodes(clock, new Random(1)), ui);
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
        return open(1, UI);
    }

    private ViewerSession open(int protocolVersion, Executor executor) throws Exception {
        ViewerSession session = ViewerSession.open("127.0.0.1", port, ui.lastCode(), protocolVersion, executor,
                        listener)
                .get(WAIT.toMillis(), TimeUnit.MILLISECONDS);
        sessions.add(session);
        return session;
    }

    /** Opens a v2 session to {@code agent}, which shows a {@code width x height} screen. */
    private ViewerSession openToFake(FakeAgent agent, int width, int height) throws Exception {
        return openToFake(agent, 2, width, height);
    }

    /** Opens a session of {@code version} to {@code agent}, which shows a {@code width x height} screen. */
    private ViewerSession openToFake(FakeAgent agent, int version, int width, int height) throws Exception {
        CompletableFuture<ViewerSession> future =
                ViewerSession.open("127.0.0.1", agent.port(), "123456", version, UI, listener);
        agent.handshake(version, new ScreenInfo(width, height, 100), WAIT);
        ViewerSession session = future.get(WAIT.toMillis(), TimeUnit.MILLISECONDS);
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

        CompletableFuture<ViewerSession> future = ViewerSession.open("127.0.0.1", port, wrongCode, 1, UI, listener);

        assertThatThrownBy(() -> future.get(WAIT.toMillis(), TimeUnit.MILLISECONDS))
                .isInstanceOf(ExecutionException.class)
                .cause()
                .isInstanceOfSatisfying(AgentRejectedException.class,
                        e -> assertThat(e.reason()).isEqualTo("invalid pairing code"));
        assertThat(listener.frames()).isEmpty();
        assertThat(listener.closedReasons()).isEmpty();
    }

    @Test
    void tilesAreAckedAfterTheListener() throws Exception {
        restartServer(2, desktop);
        BlockingQueue<Runnable> uiTasks = new LinkedBlockingQueue<>();

        ViewerSession session = open(2, uiTasks::add);

        assertThat(session.protocolVersion()).isEqualTo(2);
        assertThat(poll(() -> !uiTasks.isEmpty(), WAIT)).isTrue(); // the first frame waits for the "UI"
        desktop.setImage(whiteImage(32, 16));
        // Several capture intervals (30 fps): without the ack the agent must keep the changed screen back.
        Thread.sleep(300);
        assertThat(uiTasks).hasSize(1);
        assertThat(listener.tiles()).isEmpty();
        // While the listener runs the ack must not be out yet: an early ack would let the changed screen through.
        AtomicBoolean nextQueuedDuringListener = new AtomicBoolean();
        listener.onTiles(t -> {
            listener.onTiles(u -> {});
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
            nextQueuedDuringListener.set(!uiTasks.isEmpty());
        });

        uiTasks.take().run(); // listener, then the ack

        assertThat(nextQueuedDuringListener).isFalse();
        assertThat(listener.tiles()).hasSize(1);
        Runnable second = uiTasks.poll(WAIT.toMillis(), TimeUnit.MILLISECONDS);
        assertThat(second).isNotNull();
        second.run();
        List<DecodedTiles> tiles = listener.tiles();
        assertThat(tiles).hasSize(2);
        assertThat(tiles.get(1).sequence()).isEqualTo(tiles.get(0).sequence() + 1);
        assertThat(tiles.get(1).patches()).isNotEmpty()
                .allSatisfy(p -> assertThat(p.argb()).containsOnly(0xFFFFFFFF));
    }

    @Test
    void ackFollowsTheListenerOnTheWire() throws Exception {
        try (FakeAgent agent = new FakeAgent()) {
            ViewerSession session = openToFake(agent, 32, 16);
            session.input().setViewport(Viewport.fit(32, 16, 32, 16));
            // Inside the listener: a sentinel the agent must receive before any ack of this frame.
            listener.onTiles(t -> session.input().moved(3, 4));

            agent.wire().send(solidFrame(5));

            List<Message> received = receiveUntil(agent, FrameAck.class);
            assertThat(received).filteredOn(m -> !(m instanceof Heartbeat))
                    .containsExactly(new MouseMove(3, 4), new FrameAck(5));
            assertThat(listener.tiles()).hasSize(1);
        }
    }

    @Test
    void failingListenerEndsTheSessionWithoutAck() throws Exception {
        try (FakeAgent agent = new FakeAgent()) {
            openToFake(agent, 32, 16);
            listener.onTiles(t -> {
                throw new IllegalStateException("view broken");
            });

            agent.wire().send(solidFrame(0));

            assertThat(agent.wire().receiveUntilClosed(WAIT))
                    .filteredOn(msg -> !(msg instanceof Heartbeat))
                    .containsExactly(new Bye("protocol error"));
            assertThat(poll(() -> !listener.closedReasons().isEmpty(), WAIT)).isTrue();
            assertThat(listener.closedReasons()).containsExactly("protocol error");
        }
    }

    @Test
    void listenerThrowingAnErrorEndsTheSessionWithoutAck() throws Exception {
        try (FakeAgent agent = new FakeAgent()) {
            openToFake(agent, 32, 16);
            listener.onTiles(t -> {
                throw new AssertionError("view broken");
            });

            agent.wire().send(solidFrame(0));

            assertThat(agent.wire().receiveUntilClosed(WAIT))
                    .filteredOn(msg -> !(msg instanceof Heartbeat))
                    .containsExactly(new Bye("protocol error"));
            assertThat(poll(() -> !listener.closedReasons().isEmpty(), WAIT)).isTrue();
            assertThat(listener.closedReasons()).containsExactly("protocol error");
        }
    }

    @Test
    void streamLargerThanTheAgentScreenEndsTheSession() throws Exception {
        try (FakeAgent agent = new FakeAgent()) {
            openToFake(agent, 16, 16);

            agent.wire().send(solidFrame(0)); // 32x16 on a 16x16 screen: the agent never upscales

            assertThat(agent.wire().receiveUntilClosed(WAIT))
                    .filteredOn(msg -> !(msg instanceof Heartbeat))
                    .containsExactly(new Bye("protocol error"));
            assertThat(poll(() -> !listener.closedReasons().isEmpty(), WAIT)).isTrue();
            assertThat(listener.closedReasons()).containsExactly("protocol error");
            assertThat(listener.tiles()).isEmpty();
        }
    }

    /** A complete one-part black 32x16 tile frame. */
    private static TileUpdate solidFrame(long sequence) {
        return new TileUpdate(sequence, 32, 16, true, 0,
                List.of(new TileUpdate.Rect(0, 0, 32, 16, TileUpdate.Encoding.SOLID, new byte[] {0, 0, 0, 0})));
    }

    /** Every message up to and including the first of {@code type}. */
    private static List<Message> receiveUntil(FakeAgent agent, Class<? extends Message> type) {
        List<Message> received = new ArrayList<>();
        Message m;
        do {
            m = agent.wire().receive(WAIT);
            received.add(m);
        } while (!type.isInstance(m));
        return received;
    }

    @Test
    void viewChangedSendsStreamConfigOnce() throws Exception {
        restartServer(2, new FakeDesktop(2560, 1440));
        ViewerSession session = open(2, UI);

        session.viewChanged(1600, 900, 1);
        session.viewChanged(1600, 900, 1);

        assertThat(poll(() -> listener.tiles().stream().anyMatch(t -> isSize(t, 1600, 900)), WAIT)).isTrue();
        assertThat(listener.tiles()).filteredOn(t -> isSize(t, 1600, 900)).hasSize(1);
        assertThat(listener.tiles()).allMatch(t -> isSize(t, 1600, 900) || isSize(t, 2560, 1440));
    }

    @Test
    void viewChangedSendsOnlyChangedStreamConfigs() throws Exception {
        try (FakeAgent agent = new FakeAgent()) {
            ViewerSession session = openToFake(agent, 2560, 1440);

            session.viewChanged(1600, 900, 1);
            session.viewChanged(1600, 900, 1);
            session.viewChanged(0, 900, 1); // empty view: nothing to ask for
            session.viewChanged(800, 450, 1);
            session.input().setViewport(Viewport.fit(2560, 1440, 800, 450));
            session.input().moved(1, 1); // sentinel: everything sent before it has arrived

            List<Message> received = new ArrayList<>();
            Message m;
            do {
                m = agent.wire().receive(WAIT);
                received.add(m);
            } while (!(m instanceof MouseMove));
            assertThat(received).filteredOn(StreamConfig.class::isInstance)
                    .containsExactly(new StreamConfig(1600, 900), new StreamConfig(800, 450));
        }
    }

    @Test
    void corruptTilesEndTheSession() throws Exception {
        try (FakeAgent agent = new FakeAgent()) {
            openToFake(agent, 32, 16);
            byte[] garbage = {1, 2, 3, 4, 5, 6, 7, 8};

            agent.wire().send(new TileUpdate(0, 32, 16, true, 0,
                    List.of(new TileUpdate.Rect(0, 0, 32, 16, TileUpdate.Encoding.LOSSLESS, garbage))));

            assertThat(agent.wire().receiveUntilClosed(WAIT))
                    .filteredOn(msg -> !(msg instanceof Heartbeat))
                    .containsExactly(new Bye("protocol error"));
            assertThat(poll(() -> !listener.closedReasons().isEmpty(), WAIT)).isTrue();
            assertThat(listener.closedReasons()).containsExactly("protocol error");
            assertThat(listener.tiles()).isEmpty();
        }
    }

    @Test
    void udpTransportUsesUdpDecoder() throws Exception {
        try (FakeAgent agent = new FakeAgent(); SenderUdpAgent udp = new SenderUdpAgent()) {
            ViewerSession session = openToFake(agent, 3, 32, 16);
            agent.wire().send(udp.setup());
            agent.wire().send(new TransportState(TransportState.Mode.UDP, 0));
            UdpImageSender sender = udp.sender();
            sender.newEpoch(0, 32, 16);
            sender.submit(0, 1, SenderUdpAgent.solidStream(32, 16, RectPixels.encodeSolid(0xFFFFFF)),
                    System.nanoTime());

            // Applied by the viewer and acknowledged over UDP: the sender holds nothing back any more.
            assertThat(udp.pumpUntil(() -> sender.outstandingBytes() == 0, WAIT)).isTrue();
            assertThat(listener.tiles()).singleElement().satisfies(t -> {
                assertThat(List.of(t.sequence(), (long) t.streamWidth(), (long) t.streamHeight()))
                        .containsExactly(1L, 32L, 16L);
                assertThat(t.patches()).singleElement()
                        .satisfies(p -> assertThat(p.argb()).hasSize(32 * 16).containsOnly(0xFFFFFFFF));
            });
            assertThat(sender.admitsCapture()).isTrue();
            // The UDP picture is never acknowledged over TCP: a sentinel shows everything sent before it.
            session.input().setViewport(Viewport.fit(32, 16, 32, 16));
            session.input().moved(1, 1);
            assertThat(receiveUntil(agent, MouseMove.class)).noneMatch(FrameAck.class::isInstance);
            assertThat(listener.closedReasons()).isEmpty();
        }
    }

    @Test
    void earlyRectsBeforeTransportStateApplied() throws Exception {
        try (FakeAgent agent = new FakeAgent(); SenderUdpAgent udp = new SenderUdpAgent()) {
            openToFake(agent, 3, 32, 16);
            agent.wire().send(udp.setup()); // no TransportState yet: UDP rects may overtake it
            UdpImageSender sender = udp.sender();
            sender.newEpoch(0, 32, 16);
            sender.submit(0, 4, SenderUdpAgent.solidStream(32, 16, RectPixels.encodeSolid(0x123456)),
                    System.nanoTime());

            assertThat(udp.pumpUntil(() -> sender.outstandingBytes() == 0, WAIT)).isTrue();
            assertThat(listener.tiles()).singleElement().satisfies(t -> assertThat(t.patches()).singleElement()
                    .satisfies(p -> assertThat(p.argb()).containsOnly(0xFF123456)));
            assertThat(listener.closedReasons()).isEmpty();
        }
    }

    @Test
    void udpRectsBeforeTheViewAreDroppedThenApplied() throws Exception {
        try (FakeAgent agent = new FakeAgent(); SenderUdpAgent udp = new SenderUdpAgent()) {
            openToFake(agent, 3, 32, 16);
            listener.udpViewReady(false);
            agent.wire().send(udp.setup());
            UdpImageSender sender = udp.sender();
            sender.newEpoch(0, 32, 16);
            sender.submit(0, 1, SenderUdpAgent.solidStream(32, 16, RectPixels.encodeSolid(0xFF0000)),
                    System.nanoTime());

            // Refused by the "UI": reported dropped, so the agent has to send the tiles again.
            assertThat(udp.pumpUntil(() -> sender.outstandingBytes() == 0, WAIT)).isTrue();
            assertThat(listener.tiles()).isEmpty();
            assertThat(sender.takeForced().tiles().cardinality()).isEqualTo(1);

            listener.udpViewReady(true);
            sender.submit(0, 2, SenderUdpAgent.solidStream(32, 16, RectPixels.encodeSolid(0x00FF00)),
                    System.nanoTime());
            assertThat(udp.pumpUntil(() -> sender.outstandingBytes() == 0, WAIT)).isTrue();
            assertThat(listener.tiles()).singleElement().satisfies(t -> assertThat(t.patches()).singleElement()
                    .satisfies(p -> assertThat(p.argb()).containsOnly(0xFF00FF00)));
            assertThat(sender.takeForced().tiles().isEmpty()).isTrue();
            assertThat(listener.closedReasons()).isEmpty();
        }
    }

    @Test
    void udpStreamLargerThanTheAgentScreenEndsTheSession() throws Exception {
        try (FakeAgent agent = new FakeAgent(); SenderUdpAgent udp = new SenderUdpAgent()) {
            openToFake(agent, 3, 16, 16);
            agent.wire().send(udp.setup());
            UdpImageSender sender = udp.sender();
            sender.newEpoch(0, 32, 16); // 32x16 on a 16x16 screen: the agent never upscales
            sender.submit(0, 1, SenderUdpAgent.solidStream(32, 16, RectPixels.encodeSolid(0)), System.nanoTime());

            assertThat(udp.pumpUntil(() -> !listener.closedReasons().isEmpty(), WAIT)).isTrue();
            assertThat(listener.closedReasons()).containsExactly("protocol error");
            assertThat(agent.wire().receiveUntilClosed(WAIT))
                    .filteredOn(msg -> !(msg instanceof Heartbeat))
                    .containsExactly(new Bye("protocol error"));
            assertThat(listener.tiles()).isEmpty();
        }
    }

    @Test
    void failingUdpListenerEndsTheSession() throws Exception {
        try (FakeAgent agent = new FakeAgent(); SenderUdpAgent udp = new SenderUdpAgent()) {
            openToFake(agent, 3, 32, 16);
            listener.onTiles(t -> {
                throw new IllegalStateException("view broken");
            });
            agent.wire().send(udp.setup());
            UdpImageSender sender = udp.sender();
            sender.newEpoch(0, 32, 16);
            sender.submit(0, 1, SenderUdpAgent.solidStream(32, 16, RectPixels.encodeSolid(0)), System.nanoTime());

            assertThat(udp.pumpUntil(() -> !listener.closedReasons().isEmpty(), WAIT)).isTrue();
            assertThat(listener.closedReasons()).containsExactly("protocol error");
            assertThat(agent.wire().receiveUntilClosed(WAIT))
                    .filteredOn(msg -> !(msg instanceof Heartbeat))
                    .containsExactly(new Bye("protocol error"));
        }
    }

    private static boolean isSize(DecodedTiles tiles, int width, int height) {
        return tiles.streamWidth() == width && tiles.streamHeight() == height;
    }

    private static BufferedImage whiteImage(int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, width, height);
        g.dispose();
        return image;
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
        private final List<DecodedTiles> tiles = new ArrayList<>();
        /** Runs at the start of every {@code onTiles}, before the tiles are recorded; may throw. */
        private volatile Consumer<DecodedTiles> onTilesHook = t -> {};
        private final List<String> closedReasons = new ArrayList<>();

        @Override
        public synchronized void onFrame(DecodedFrame frame) {
            frames.add(frame);
        }

        @Override
        public void onTiles(DecodedTiles decoded) {
            onTilesHook.accept(decoded);
            synchronized (this) {
                tiles.add(decoded);
            }
        }

        void onTiles(Consumer<DecodedTiles> hook) {
            onTilesHook = hook;
        }

        /** Whether UDP batches are taken (a view exists); when not, they are refused without a throw. */
        private volatile boolean udpViewReady = true;

        @Override
        public boolean onUdpTiles(DecodedTiles decoded) {
            if (!udpViewReady) {
                return false;
            }
            onTiles(decoded);
            return true;
        }

        void udpViewReady(boolean ready) {
            udpViewReady = ready;
        }

        @Override
        public synchronized void onClosed(String reason) {
            closedReasons.add(reason);
        }

        synchronized List<DecodedFrame> frames() {
            return List.copyOf(frames);
        }

        synchronized List<DecodedTiles> tiles() {
            return List.copyOf(tiles);
        }

        synchronized List<String> closedReasons() {
            return List.copyOf(closedReasons);
        }
    }
}

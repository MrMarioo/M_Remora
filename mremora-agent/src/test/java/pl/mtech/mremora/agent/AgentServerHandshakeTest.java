package pl.mtech.mremora.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import pl.mtech.mremora.protocol.Bye;
import pl.mtech.mremora.protocol.FrameUpdate;
import pl.mtech.mremora.protocol.Hello;
import pl.mtech.mremora.protocol.Message;
import pl.mtech.mremora.protocol.MouseMove;
import pl.mtech.mremora.protocol.ScreenInfo;
import pl.mtech.mremora.testkit.MutableClock;
import pl.mtech.mremora.testkit.WireClient;

class AgentServerHandshakeTest {

    /** Generous upper bound for anything the server should do "now"; only reached when a test fails. */
    private static final Duration WAIT = Duration.ofSeconds(5);
    private static final Duration SHORT_HANDSHAKE = Duration.ofMillis(300);

    private final MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
    private final RecordingUi ui = new RecordingUi();
    private final FakeScreen screen = new FakeScreen(32, 16);
    private final List<WireClient> clients = new ArrayList<>();
    private AgentServer server;
    private int port;

    @AfterEach
    void tearDown() {
        clients.forEach(WireClient::close);
        if (server != null) {
            server.close();
        }
    }

    private void startServer(Duration handshakeTimeout) {
        // Long heartbeat and idle: no heartbeat between expected messages, and only the handshake timer
        // may close a silent connection.
        AgentConfig config = new AgentConfig(0, handshakeTimeout, Duration.ofSeconds(60), Duration.ofSeconds(60), 30);
        server = new AgentServer(config, screen, screen, new PairingCodes(clock, new Random(1)), ui);
        port = server.start();
    }

    private WireClient connect() {
        WireClient client = WireClient.connect(port);
        clients.add(client);
        return client;
    }

    private static Hello viewerHello(String code) {
        return new Hello(1, Hello.Role.VIEWER, code);
    }

    private String wrongCode() {
        return ui.lastCode().equals("000000") ? "000001" : "000000";
    }

    /** Connects with the code currently shown and checks the agent's reply. */
    private WireClient authenticate() {
        WireClient client = connect();
        client.send(viewerHello(ui.lastCode()));
        assertThat(client.receive(WAIT)).isEqualTo(new Hello(1, Hello.Role.AGENT, ""));
        assertThat(client.receive(WAIT)).isEqualTo(new ScreenInfo(32, 16, 100));
        return client;
    }

    /** Expects {@code Bye(reason)} as the last message; frames of a running session may come before it. */
    private void expectByeAndClose(WireClient client, String reason) {
        List<Message> messages = client.receiveUntilClosed(WAIT);
        assertThat(messages).isNotEmpty();
        assertThat(messages.getLast()).isEqualTo(new Bye(reason));
        assertThat(messages.subList(0, messages.size() - 1)).allMatch(FrameUpdate.class::isInstance);
    }

    @Test
    void startShowsCode() {
        startServer(SHORT_HANDSHAKE);

        assertThat(ui.lastCode()).matches("\\d{6}");
    }

    @Test
    void correctCodeGetsHelloThenScreenInfo() throws InterruptedException {
        startServer(SHORT_HANDSHAKE);

        authenticate();

        assertThat(ui.awaitCall("session ", WAIT)).isTrue();
        assertThat(ui.calls()).contains("session 127.0.0.1");
    }

    @Test
    void newerViewerGetsVersionOne() {
        startServer(SHORT_HANDSHAKE);
        WireClient client = connect();

        client.send(new Hello(7, Hello.Role.VIEWER, ui.lastCode()));

        assertThat(client.receive(WAIT)).isEqualTo(new Hello(1, Hello.Role.AGENT, ""));
    }

    @Test
    void wrongCodeGetsByeAndClose() {
        startServer(SHORT_HANDSHAKE);
        WireClient client = connect();

        client.send(viewerHello(wrongCode()));

        expectByeAndClose(client, "invalid pairing code");
    }

    @Test
    void retryWithinOneSecondIsTooSoon() {
        startServer(SHORT_HANDSHAKE);
        WireClient first = connect();
        first.send(viewerHello(wrongCode()));
        expectByeAndClose(first, "invalid pairing code");

        WireClient second = connect();
        second.send(viewerHello(ui.lastCode()));

        expectByeAndClose(second, "too many attempts");
    }

    @Test
    void fifthWrongCodeLocks() throws InterruptedException {
        startServer(SHORT_HANDSHAKE);
        String shownCode = ui.lastCode();
        for (int i = 0; i < PairingCodes.MAX_FAILURES; i++) {
            WireClient client = connect();
            client.send(viewerHello(wrongCode()));
            expectByeAndClose(client, "invalid pairing code");
            clock.advance(Duration.ofSeconds(1));
        }

        WireClient client = connect();
        client.send(viewerHello(shownCode));

        expectByeAndClose(client, "locked");
        assertThat(ui.awaitCall("locked", WAIT)).isTrue();
    }

    @Test
    void secondUnauthenticatedConnectionIsBusy() {
        // Long handshake timeout, so the first connection cannot time out while the second one is admitted.
        startServer(Duration.ofSeconds(30));
        WireClient first = connect();

        WireClient second = connect();

        expectByeAndClose(second, "busy");
        first.send(viewerHello(ui.lastCode()));
        assertThat(first.receive(WAIT)).isEqualTo(new Hello(1, Hello.Role.AGENT, ""));
    }

    @Test
    void connectionDuringSessionIsBusy() {
        startServer(SHORT_HANDSHAKE);
        authenticate();

        WireClient second = connect();

        expectByeAndClose(second, "busy");
    }

    @Test
    void silentConnectionTimesOutWithoutChangingCode() {
        startServer(SHORT_HANDSHAKE);
        String code = ui.lastCode();

        for (int i = 0; i < 6; i++) {
            WireClient client = connect();
            expectByeAndClose(client, "timeout");
        }

        assertThat(ui.lastCode()).isEqualTo(code);
        assertThat(ui.calls()).doesNotContain("locked");
        authenticate();
    }

    @Test
    void garbageIsProtocolErrorNotAttempt() {
        startServer(SHORT_HANDSHAKE);
        String code = ui.lastCode();
        WireClient client = connect();

        client.sendRaw(new byte[] {0, 0, 0, 2, 0x7F, 0});

        expectByeAndClose(client, "protocol error");
        assertThat(ui.lastCode()).isEqualTo(code);
        authenticate();
    }

    @Test
    void oversizedFrameIsRejected() {
        startServer(SHORT_HANDSHAKE);
        WireClient client = connect();

        client.sendRaw(new byte[] {0x00, 0x03, 0x0D, 0x40}); // length 200_000 > 128 KiB

        expectByeAndClose(client, "protocol error");
    }

    @Test
    void agentRoleHelloIsProtocolError() {
        startServer(SHORT_HANDSHAKE);
        WireClient client = connect();

        client.send(new Hello(1, Hello.Role.AGENT, ui.lastCode()));

        expectByeAndClose(client, "protocol error");
        authenticate();
    }

    @Test
    void nonHelloFirstMessageIsProtocolError() {
        startServer(SHORT_HANDSHAKE);
        WireClient client = connect();

        client.send(new MouseMove(1, 2));

        expectByeAndClose(client, "protocol error");
        authenticate();
    }

    @Test
    void endingSessionFromUiFreesServerWithNewCode() throws InterruptedException {
        startServer(SHORT_HANDSHAKE);
        String usedCode = ui.lastCode();
        WireClient viewer = authenticate();
        assertThat(ui.awaitCall("session ", WAIT)).isTrue();

        ui.endSession();

        expectByeAndClose(viewer, "ended by user");
        assertThat(awaitCodeOtherThan(usedCode)).isTrue();
        authenticate();
    }

    @Test
    void closeSaysShuttingDown() {
        startServer(SHORT_HANDSHAKE);
        WireClient viewer = authenticate();

        server.close();

        expectByeAndClose(viewer, "agent shutting down");
    }

    @Test
    void startOnTakenPortFailsAndCloseIsSafe() {
        startServer(SHORT_HANDSHAKE);
        AgentServer second = new AgentServer(AgentConfig.defaults(port), screen, screen,
                new PairingCodes(clock, new Random(2)), new RecordingUi());

        assertThatThrownBy(second::start).isInstanceOf(java.net.BindException.class);
        second.close();
        authenticate();
    }

    private boolean awaitCodeOtherThan(String code) throws InterruptedException {
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (System.nanoTime() < deadline) {
            if (!code.equals(ui.lastCode())) {
                return true;
            }
            Thread.sleep(10);
        }
        return false;
    }
}

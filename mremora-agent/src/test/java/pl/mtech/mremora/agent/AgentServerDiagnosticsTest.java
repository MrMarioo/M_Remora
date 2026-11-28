package pl.mtech.mremora.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Random;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import pl.mtech.mremora.protocol.netty.LinkImpairment;
import pl.mtech.mremora.testkit.MutableClock;

/** The public test and developer hooks of {@link AgentServer}: UDP impairment, startup timeout, image statistics. */
class AgentServerDiagnosticsTest {

    private static final Duration WAIT = Duration.ofSeconds(5);
    private static final Duration LONG = Duration.ofSeconds(60);

    private final MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
    private final FakeScreen screen = new FakeScreen(32, 16);
    private AgentServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.close();
        }
    }

    private AgentServer server(int maxProtocolVersion) {
        AgentConfig config = new AgentConfig(0, Duration.ofSeconds(30), LONG, LONG, 30, maxProtocolVersion);
        server = new AgentServer(config, screen, screen, new PairingCodes(clock, new Random(1)), new RecordingUi());
        return server;
    }

    @Test
    void noImageStatsWithoutUdp() {
        server(2).start();

        assertThat(server.udpImageStats()).isEmpty();
    }

    @Test
    void zeroImageStatsBeforeAnySession() {
        server(3).start();

        assertThat(server.udpImageStats()).contains(UdpImageStats.ZERO);
    }

    @Test
    void injectorDropsAreCountedLive() throws Exception {
        server(3).udpImpairment(new LinkImpairment(0, 0, Duration.ZERO, Duration.ZERO, true, false, 0));
        server.start();
        int udpPort = server.udpPort();

        try (DatagramSocket socket = new DatagramSocket()) {
            byte[] data = new byte[100];
            for (int i = 0; i < 3; i++) {
                socket.send(new DatagramPacket(data, data.length, InetAddress.getLoopbackAddress(), udpPort));
            }
        }

        long deadline = System.nanoTime() + WAIT.toNanos();
        while (server.udpImageStats().orElseThrow().injectorDrops() < 3 && System.nanoTime() - deadline < 0) {
            Thread.sleep(10);
        }
        assertThat(server.udpImageStats().orElseThrow().injectorDrops()).isEqualTo(3);
    }

    @Test
    void publishedStatsCarryLiveInjectorDrops() {
        server(3).start();
        UdpImageStats published = new UdpImageStats(10, 2, 4, 9, 3, 5, 1_500_000, 999, 777);

        server.publishUdpImageStats(published);

        Optional<UdpImageStats> stats = server.udpImageStats();
        assertThat(stats).contains(new UdpImageStats(10, 2, 4, 9, 3, 5, 1_500_000, 0, 0));
    }

    @Test
    void hooksRejectCallsAfterStart() {
        server(3).start();

        assertThatThrownBy(() -> server.udpImpairment(LinkImpairment.NONE)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> server.udpStartupTimeout(Duration.ofSeconds(1)))
                .isInstanceOf(IllegalStateException.class);
    }
}

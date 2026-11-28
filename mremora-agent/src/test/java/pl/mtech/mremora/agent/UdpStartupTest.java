package pl.mtech.mremora.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import pl.mtech.mremora.protocol.TransportState.Mode;
import pl.mtech.mremora.testkit.MutableClock;

class UdpStartupTest {

    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-27T10:00:00Z"));
    private final UdpStartup startup = new UdpStartup(clock::nanoTime, UdpStartup.TIMEOUT);

    @Test
    void timeoutIsTwoSeconds() {
        assertThat(UdpStartup.TIMEOUT).isEqualTo(Duration.ofSeconds(2));
    }

    @Test
    void pendingBeforeTimeout() {
        assertThat(startup.outcome()).isEmpty();
        clock.advance(Duration.ofMillis(1999));
        assertThat(startup.outcome()).isEmpty();
    }

    @Test
    void udpOnRoundTrip() {
        clock.advance(Duration.ofMillis(300));
        startup.roundTripSucceeded();
        assertThat(startup.outcome()).contains(Mode.UDP);
    }

    @Test
    void tcpAfterTimeout() {
        clock.advance(Duration.ofMillis(2000));
        assertThat(startup.outcome()).contains(Mode.TCP);
    }

    @Test
    void lateRoundTripDoesNotFlip() {
        clock.advance(Duration.ofMillis(2000));
        assertThat(startup.outcome()).contains(Mode.TCP);
        startup.roundTripSucceeded();
        assertThat(startup.outcome()).contains(Mode.TCP);
    }

    @Test
    void roundTripAfterDeadlineIsTcpEvenIfNobodyAskedYet() {
        clock.advance(Duration.ofMillis(2500));
        startup.roundTripSucceeded();
        assertThat(startup.outcome()).contains(Mode.TCP);
    }

    @Test
    void timeoutAfterUdpDoesNotFlip() {
        startup.roundTripSucceeded();
        clock.advance(Duration.ofSeconds(10));
        assertThat(startup.outcome()).contains(Mode.UDP);
    }

    @Test
    void deadlineSurvivesNanoTimeOverflow() {
        long[] now = {Long.MAX_VALUE - 1_000};
        UdpStartup wrapping = new UdpStartup(() -> now[0], Duration.ofNanos(2_000));
        now[0] += 1_999; // wraps past Long.MAX_VALUE, like System.nanoTime() may
        assertThat(wrapping.outcome()).isEmpty();
        now[0] += 1;
        assertThat(wrapping.outcome()).contains(Mode.TCP);
    }
}

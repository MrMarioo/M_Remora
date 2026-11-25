package pl.mtech.mremora.viewer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import pl.mtech.mremora.testkit.MutableClock;

class FrameStatsTest {

    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-25T10:00:00Z"));
    private final FrameStats stats = new FrameStats(clock);

    @Test
    void emptyIsZero() {
        assertThat(stats.snapshot()).isEqualTo(new FrameStats.Snapshot(0, 0));
    }

    @Test
    void countsFramesInLastSecond() {
        recordThirtyFrames();

        FrameStats.Snapshot snapshot = stats.snapshot();
        assertThat(snapshot.fps()).isEqualTo(30.0);
        assertThat(snapshot.megabitsPerSecond()).isCloseTo(3.0, within(0.01));
    }

    @Test
    void oldFramesExpire() {
        recordThirtyFrames();

        clock.advance(Duration.ofSeconds(2));

        assertThat(stats.snapshot()).isEqualTo(new FrameStats.Snapshot(0, 0));
    }

    @Test
    void frameExactlyOneSecondOldIsOutOfWindow() {
        stats.record(12_500);

        clock.advance(Duration.ofSeconds(1));

        assertThat(stats.snapshot()).isEqualTo(new FrameStats.Snapshot(0, 0));
    }

    @Test
    void frameJustUnderOneSecondOldIsInWindow() {
        stats.record(12_500);

        clock.advance(Duration.ofMillis(999));

        assertThat(stats.snapshot().fps()).isEqualTo(1.0);
    }

    private void recordThirtyFrames() {
        for (int i = 0; i < 30; i++) {
            stats.record(12_500);
            clock.advance(Duration.ofMillis(33));
        }
    }
}

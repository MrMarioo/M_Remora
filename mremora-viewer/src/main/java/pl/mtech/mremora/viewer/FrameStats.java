package pl.mtech.mremora.viewer;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Objects;

/**
 * Frame rate and bandwidth over the last second, for the viewer's overlay.
 *
 * <p>Not thread-safe: used only on the UI thread.
 */
final class FrameStats {

    private static final Duration WINDOW = Duration.ofSeconds(1);

    /** Frames per second and megabits per second of encoded data, both over the last second. */
    record Snapshot(double fps, double megabitsPerSecond) {}

    private record Sample(Instant at, int bytes) {}

    private final Clock clock;
    private final ArrayDeque<Sample> samples = new ArrayDeque<>();
    private long bytesInWindow;

    FrameStats(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Records one frame received now, with its encoded size. */
    void record(int encodedBytes) {
        Instant now = clock.instant();
        evictOlderThanWindow(now);
        samples.addLast(new Sample(now, encodedBytes));
        bytesInWindow += encodedBytes;
    }

    /** Statistics of frames recorded strictly within the last second. */
    Snapshot snapshot() {
        evictOlderThanWindow(clock.instant());
        return new Snapshot(samples.size(), bytesInWindow * 8 / 1_000_000.0);
    }

    private void evictOlderThanWindow(Instant now) {
        Instant oldestKept = now.minus(WINDOW);
        while (!samples.isEmpty() && !samples.peekFirst().at().isAfter(oldestKept)) {
            bytesInWindow -= samples.removeFirst().bytes();
        }
    }
}

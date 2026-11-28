package pl.mtech.mremora.testkit;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A {@link Clock} in UTC that only moves when a test calls {@link #advance(Duration)}; {@link #nanoTime()} is the
 * matching fake of {@link System#nanoTime()} for code that measures elapsed time. Thread-safe.
 */
public final class MutableClock extends Clock {

    private final Instant start;
    private final AtomicReference<Instant> now;

    public MutableClock(Instant start) {
        this.start = start;
        this.now = new AtomicReference<>(start);
    }

    /** Nanoseconds the clock was advanced since construction: a fake {@link System#nanoTime()}. */
    public long nanoTime() {
        return Duration.between(start, now.get()).toNanos();
    }

    /** Moves the clock forward by {@code d}. */
    public void advance(Duration d) {
        now.updateAndGet(current -> current.plus(d));
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        if (!ZoneOffset.UTC.equals(zone)) {
            throw new UnsupportedOperationException("MutableClock is always UTC");
        }
        return this;
    }

    @Override
    public Instant instant() {
        return now.get();
    }
}

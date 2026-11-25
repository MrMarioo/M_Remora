package pl.mtech.mremora.testkit;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

/** A {@link Clock} in UTC that only moves when a test calls {@link #advance(Duration)}. Thread-safe. */
public final class MutableClock extends Clock {

    private final AtomicReference<Instant> now;

    public MutableClock(Instant start) {
        this.now = new AtomicReference<>(start);
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

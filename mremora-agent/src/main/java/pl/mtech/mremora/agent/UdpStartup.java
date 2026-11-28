package pl.mtech.mremora.agent;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.function.LongSupplier;
import pl.mtech.mremora.protocol.TransportState;

/**
 * Decides once per session whether UDP works: {@code UDP} when an agent probe made a full round trip
 * (agent → viewer → agent) before {@code timeout} elapsed, otherwise {@code TCP}. The decision never changes.
 * Time is measured from construction on a monotonic clock ({@link System#nanoTime()} in production), so a step of
 * the wall clock can neither stall nor cut short the wait. Not thread-safe (event loop).
 */
final class UdpStartup {

    static final Duration TIMEOUT = Duration.ofSeconds(2);

    private final LongSupplier nanoTime;
    private final long startNanos;
    private final long timeoutNanos;
    private TransportState.Mode decided;

    /** @param nanoTime monotonic nanoseconds, e.g. {@code System::nanoTime} */
    UdpStartup(LongSupplier nanoTime, Duration timeout) {
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
        this.timeoutNanos = Objects.requireNonNull(timeout, "timeout").toNanos();
        this.startNanos = nanoTime.getAsLong();
    }

    /** A reply to one of the agent's probes arrived; too late (after the deadline) means TCP. */
    void roundTripSucceeded() {
        if (decided == null) {
            decided = expired() ? TransportState.Mode.TCP : TransportState.Mode.UDP;
        }
    }

    /** Empty while undecided; afterwards always the same mode. */
    Optional<TransportState.Mode> outcome() {
        if (decided == null && expired()) {
            decided = TransportState.Mode.TCP;
        }
        return Optional.ofNullable(decided);
    }

    private boolean expired() {
        return nanoTime.getAsLong() - startNanos >= timeoutNanos; // difference: safe across nanoTime overflow
    }
}

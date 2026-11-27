package pl.mtech.mremora.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import pl.mtech.mremora.protocol.TileUpdate;

class TileCaptureLoopTest {

    /** Generous upper bound for anything that should happen "now"; only reached when a test fails. */
    private static final Duration WAIT = Duration.ofSeconds(5);

    private final AckGate gate = new AckGate();
    private final AtomicInteger encodes = new AtomicInteger();
    private final List<RuntimeException> failures = new CopyOnWriteArrayList<>();
    private Thread thread;

    @AfterEach
    void tearDown() {
        if (thread != null) {
            thread.interrupt();
        }
    }

    private Thread start(Supplier<List<TileUpdate>> encoder) {
        Supplier<List<TileUpdate>> counted = () -> {
            encodes.incrementAndGet();
            return encoder.get();
        };
        var loop = new TileCaptureLoop(counted, gate, parts -> { }, failures::add, Duration.ofMillis(1));
        thread = Thread.ofPlatform().start(loop);
        return thread;
    }

    @Test
    void closedGateEndsTheLoopWithoutInterrupt() throws InterruptedException {
        gate.sent(0);
        Thread t = start(List::of);
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (t.getState() != Thread.State.WAITING && System.nanoTime() < deadline) {
            Thread.sleep(1);
        }
        assertThat(t.getState()).isEqualTo(Thread.State.WAITING);

        gate.close();

        t.join(WAIT.toMillis());
        assertThat(t.isAlive()).isFalse();
        assertThat(encodes.get()).isZero();
    }

    @Test
    void encoderFailureIsReportedOnceAndEndsTheLoop() throws InterruptedException {
        var boom = new IllegalStateException("boom");
        Thread t = start(() -> {
            throw boom;
        });

        t.join(WAIT.toMillis());

        assertThat(t.isAlive()).isFalse();
        assertThat(failures).containsExactly(boom);
        assertThat(encodes.get()).isEqualTo(1);
    }
}

package pl.mtech.mremora.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class AckGateTest {

    /** Generous upper bound for a wake-up that should happen "now"; only reached when a test fails. */
    private static final Duration WAIT = Duration.ofSeconds(5);

    private final AckGate gate = new AckGate();
    private Thread waiter;

    @AfterEach
    void tearDown() {
        if (waiter != null) {
            waiter.interrupt();
        }
    }

    /** Starts a thread blocked in {@code awaitFree}; the returned latch opens once it got through. */
    private CountDownLatch startWaiter() throws InterruptedException {
        var freed = new CountDownLatch(1);
        waiter = Thread.ofPlatform().start(() -> {
            try {
                gate.awaitFree();
                freed.countDown();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        awaitWaiting(waiter);
        return freed;
    }

    /** Waits until {@code t} is parked, so the test really exercises the wake-up, not a race won early. */
    private static void awaitWaiting(Thread t) throws InterruptedException {
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (t.getState() != Thread.State.WAITING && System.nanoTime() < deadline) {
            Thread.sleep(1);
        }
        assertThat(t.getState()).isEqualTo(Thread.State.WAITING);
    }

    private static boolean isFree(AckGate gate) throws InterruptedException {
        var probe = Thread.ofPlatform().start(() -> {
            try {
                gate.awaitFree();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        probe.join(WAIT.toMillis());
        boolean free = !probe.isAlive();
        probe.interrupt();
        return free;
    }

    @Test
    void freeAtStart() throws InterruptedException {
        assertThat(isFree(gate)).isTrue();
    }

    @Test
    void sentBlocksUntilAcked() throws InterruptedException {
        gate.sent(5);
        CountDownLatch freed = startWaiter();

        assertThat(freed.getCount()).isEqualTo(1);
        assertThat(gate.acked(5)).isTrue();

        assertThat(freed.await(WAIT.toMillis(), TimeUnit.MILLISECONDS)).isTrue();
    }

    @Test
    void staleAckDoesNotFree() throws InterruptedException {
        gate.sent(5);
        CountDownLatch freed = startWaiter();

        assertThat(gate.acked(4)).isFalse();

        assertThat(waiter.getState()).isEqualTo(Thread.State.WAITING);
        assertThat(freed.getCount()).isEqualTo(1);
        assertThat(gate.acked(5)).isTrue();
        assertThat(freed.await(WAIT.toMillis(), TimeUnit.MILLISECONDS)).isTrue();
    }

    @Test
    void duplicateAckDoesNotFreeTwice() throws InterruptedException {
        gate.sent(5);
        assertThat(gate.acked(5)).isTrue();
        gate.sent(6);
        CountDownLatch freed = startWaiter();

        assertThat(gate.acked(5)).isFalse();

        assertThat(waiter.getState()).isEqualTo(Thread.State.WAITING);
        assertThat(freed.getCount()).isEqualTo(1);
        assertThat(gate.acked(6)).isTrue(); // still held for 6, not freed by the duplicate
        assertThat(freed.await(WAIT.toMillis(), TimeUnit.MILLISECONDS)).isTrue();
    }

    @Test
    void ackWhileFreeIsIgnored() {
        assertThat(gate.acked(0)).isFalse();
    }

    @Test
    void closeWakesAWaiter() throws InterruptedException {
        gate.sent(5);
        CountDownLatch freed = startWaiter();

        gate.close();

        assertThat(freed.await(WAIT.toMillis(), TimeUnit.MILLISECONDS)).isTrue();
    }

    @Test
    void closedGateNeverBlocksAgain() throws InterruptedException {
        gate.close();
        gate.sent(7);

        assertThat(isFree(gate)).isTrue();
    }
}

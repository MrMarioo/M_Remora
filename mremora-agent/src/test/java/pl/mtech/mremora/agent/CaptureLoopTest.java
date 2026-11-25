package pl.mtech.mremora.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import pl.mtech.mremora.protocol.FrameUpdate;

class CaptureLoopTest {

    private static final Duration ONE_MS = Duration.ofMillis(1);

    private final AtomicInteger produced = new AtomicInteger();
    private final AtomicInteger ready = new AtomicInteger();
    private final List<RuntimeException> failures = new CopyOnWriteArrayList<>();
    private final BlockingQueue<FrameUpdate> slot = new ArrayBlockingQueue<>(1);
    private Thread thread;

    @AfterEach
    void tearDown() throws InterruptedException {
        if (thread != null) {
            thread.interrupt();
            thread.join(2000);
        }
    }

    private Supplier<FrameUpdate> counting() {
        return () -> new FrameUpdate(produced.getAndIncrement(), FrameUpdate.Format.JPEG, new byte[] {1});
    }

    private void start(Supplier<FrameUpdate> producer, Duration minInterval) {
        CaptureLoop loop = new CaptureLoop(producer, slot, ready::incrementAndGet, failures::add, minInterval);
        thread = Thread.ofPlatform().name("mremora-capture").start(loop);
    }

    @Test
    void producesAtMostOneAheadWhenNobodyTakes() throws Exception {
        start(counting(), ONE_MS);
        Thread.sleep(300);
        assertThat(produced.get()).isEqualTo(2);
        assertThat(slot).hasSize(1);
    }

    @Test
    void takingFrameLetsNextThrough() throws Exception {
        start(counting(), ONE_MS);
        for (long expected = 0; expected < 3; expected++) {
            FrameUpdate f = slot.poll(5, TimeUnit.SECONDS);
            assertThat(f).isNotNull();
            assertThat(f.sequence()).isEqualTo(expected);
        }
    }

    @Test
    void respectsMinInterval() throws Exception {
        start(counting(), Duration.ofMillis(50));
        long begin = System.nanoTime();
        for (int i = 0; i < 6; i++) {
            assertThat(slot.poll(5, TimeUnit.SECONDS)).isNotNull();
        }
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begin);
        // 6 frames need 5 full intervals between capture starts.
        assertThat(elapsedMs).isGreaterThanOrEqualTo(240);
    }

    @Test
    void producerFailureReportedOnceAndLoopEnds() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        start(() -> {
            if (calls.incrementAndGet() == 2) {
                throw new IllegalStateException("boom");
            }
            return new FrameUpdate(0, FrameUpdate.Format.JPEG, new byte[] {1});
        }, ONE_MS);
        slot.poll(5, TimeUnit.SECONDS);
        thread.join(1000);
        assertThat(thread.isAlive()).isFalse();
        assertThat(failures).hasSize(1);
        assertThat(failures.get(0)).hasMessage("boom");
    }

    @Test
    void interruptEndsLoopSilently() throws Exception {
        start(counting(), ONE_MS);
        Thread.sleep(200); // second frame is now blocked on put
        thread.interrupt();
        thread.join(1000);
        assertThat(thread.isAlive()).isFalse();
        assertThat(failures).isEmpty();
    }

    @Test
    void interruptDuringProducerSurfacingAsRuntimeExceptionEndsSilently() throws Exception {
        start(() -> {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("wrapped interrupt");
        }, ONE_MS);
        thread.join(1000);
        assertThat(thread.isAlive()).isFalse();
        assertThat(failures).isEmpty();
    }

    @Test
    void notifiesAfterEachFrame() throws Exception {
        start(counting(), ONE_MS);
        for (int i = 0; i < 4; i++) {
            assertThat(slot.poll(5, TimeUnit.SECONDS)).isNotNull();
        }
        Thread.sleep(200);
        // 4 taken + 1 sitting in the slot; the next one is blocked on put (not yet notified).
        assertThat(ready.get()).isEqualTo(5);
        assertThat(slot).hasSize(1);
    }
}

package pl.mtech.mremora.agent;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.function.Consumer;
import java.util.function.Supplier;
import pl.mtech.mremora.protocol.FrameUpdate;

/**
 * Capture thread body: produces frames into a one-slot queue, blocking while the slot is full,
 * and never starting captures more often than {@code minInterval}. Interrupting the thread ends the
 * loop silently; a {@link RuntimeException} from the producer is reported once to {@code onFailure}
 * and ends the loop.
 */
final class CaptureLoop implements Runnable {

    private final Supplier<FrameUpdate> producer;
    private final BlockingQueue<FrameUpdate> slot;
    private final Runnable onFrameReady;
    private final Consumer<RuntimeException> onFailure;
    private final long minIntervalNanos;

    CaptureLoop(Supplier<FrameUpdate> producer, BlockingQueue<FrameUpdate> slot, Runnable onFrameReady,
            Consumer<RuntimeException> onFailure, Duration minInterval) {
        this.producer = Objects.requireNonNull(producer, "producer");
        this.slot = Objects.requireNonNull(slot, "slot");
        this.onFrameReady = Objects.requireNonNull(onFrameReady, "onFrameReady");
        this.onFailure = Objects.requireNonNull(onFailure, "onFailure");
        this.minIntervalNanos = minInterval.toNanos();
    }

    @Override
    public void run() {
        try {
            while (!Thread.currentThread().isInterrupted()) {
                long started = System.nanoTime();
                FrameUpdate frame;
                try {
                    frame = producer.get();
                } catch (RuntimeException e) {
                    if (Thread.currentThread().isInterrupted()) {
                        return; // interrupt surfaced as a runtime exception: normal end of session
                    }
                    onFailure.accept(e);
                    return;
                }
                slot.put(frame);
                onFrameReady.run();
                long remaining = minIntervalNanos - (System.nanoTime() - started);
                if (remaining > 0) {
                    Thread.sleep(remaining / 1_000_000, (int) (remaining % 1_000_000));
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

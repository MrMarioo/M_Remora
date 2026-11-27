package pl.mtech.mremora.agent;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;
import pl.mtech.mremora.protocol.TileUpdate;

/**
 * Capture thread body for v2: waits until the previous frame was acked, encodes the next one and hands its parts
 * on. An unchanged screen yields no frame and does not take the gate. Captures never start more often than
 * {@code minInterval}. Closing the gate or interrupting the thread ends the loop silently; a
 * {@link RuntimeException} from the encoder is reported once to {@code onFailure} and ends the loop.
 */
final class TileCaptureLoop implements Runnable {

    private final Supplier<List<TileUpdate>> encoder;
    private final AckGate gate;
    private final Consumer<List<TileUpdate>> onFrame;
    private final Consumer<RuntimeException> onFailure;
    private final long minIntervalNanos;

    /**
     * @param encoder   the parts of the next frame, empty when nothing changed
     * @param onFrame   receives every non-empty frame; the gate is already taken for its sequence
     */
    TileCaptureLoop(Supplier<List<TileUpdate>> encoder, AckGate gate, Consumer<List<TileUpdate>> onFrame,
            Consumer<RuntimeException> onFailure, Duration minInterval) {
        this.encoder = Objects.requireNonNull(encoder, "encoder");
        this.gate = Objects.requireNonNull(gate, "gate");
        this.onFrame = Objects.requireNonNull(onFrame, "onFrame");
        this.onFailure = Objects.requireNonNull(onFailure, "onFailure");
        this.minIntervalNanos = minInterval.toNanos();
    }

    @Override
    public void run() {
        try {
            while (!Thread.currentThread().isInterrupted()) {
                gate.awaitFree();
                if (gate.isClosed()) {
                    return; // the session ended; no interrupt needed to stop here
                }
                long started = System.nanoTime();
                List<TileUpdate> parts;
                try {
                    parts = encoder.get();
                } catch (RuntimeException e) {
                    if (Thread.currentThread().isInterrupted()) {
                        return; // interrupt surfaced as a runtime exception: normal end of session
                    }
                    onFailure.accept(e);
                    return;
                }
                if (!parts.isEmpty()) {
                    // Before handing on: the ack can only come after the viewer got the frame.
                    gate.sent(parts.getFirst().sequence());
                    onFrame.accept(parts);
                }
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

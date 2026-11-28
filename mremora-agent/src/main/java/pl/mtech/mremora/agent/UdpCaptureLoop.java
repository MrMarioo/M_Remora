package pl.mtech.mremora.agent;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import pl.mtech.mremora.protocol.udp.TileDelivery;

/**
 * Capture thread body for the picture over UDP (v3): like {@link TileCaptureLoop}, but gated by the UDP sender's
 * capture gate instead of a {@code FrameAck}. Each round parks until the channel's published gate admits a capture,
 * then lets the loop decide and take the forced tiles in one step ({@link UdpImageChannel#takeForcedIfAdmitted()}),
 * encodes the changed plus forced tiles (forced ones of another epoch are ignored by the encoder) and submits a
 * non-empty capture. Captures never start more often than {@code minInterval}.
 *
 * <p>Interrupting the thread (the session's end, which interrupts before it closes the channel) ends the loop
 * silently. A channel closed without an interrupt (the UDP picture died mid-session, e.g. its sender failed) ends it
 * with one {@code WARNING}. A {@link RuntimeException} from the encoder or the gate is reported once to
 * {@code onFailure} and ends the loop (forced tiles taken for that capture are lost with the session, which ends).
 */
final class UdpCaptureLoop implements Runnable {

    private static final Logger LOG = System.getLogger(UdpCaptureLoop.class.getName());

    private final Function<TileDelivery.Forced, TileFrameEncoder.UdpFrame> encoder;
    private final UdpImageChannel channel;
    private final Consumer<RuntimeException> onFailure;
    private final long minIntervalNanos;

    /** @param encoder the next capture with {@code forced} merged in ({@link TileFrameEncoder#nextForUdp}) */
    UdpCaptureLoop(Function<TileDelivery.Forced, TileFrameEncoder.UdpFrame> encoder, UdpImageChannel channel,
                   Consumer<RuntimeException> onFailure, Duration minInterval) {
        this.encoder = Objects.requireNonNull(encoder, "encoder");
        this.channel = Objects.requireNonNull(channel, "channel");
        this.onFailure = Objects.requireNonNull(onFailure, "onFailure");
        this.minIntervalNanos = minInterval.toNanos();
    }

    @Override
    public void run() {
        try {
            while (!Thread.currentThread().isInterrupted()) {
                if (!channel.awaitAdmits()) {
                    closedUnderneath();
                    return;
                }
                long started = System.nanoTime();
                TileFrameEncoder.UdpFrame frame;
                try {
                    Optional<TileDelivery.Forced> admitted = channel.takeForcedIfAdmitted();
                    if (admitted.isEmpty()) {
                        if (channel.isClosed()) {
                            closedUnderneath();
                            return;
                        }
                        continue; // the published gate was stale; it is republished closed now
                    }
                    frame = encoder.apply(admitted.get());
                } catch (RuntimeException e) {
                    if (Thread.currentThread().isInterrupted() || channel.isClosed()) {
                        return; // interrupt surfaced as a runtime exception: normal end of session
                    }
                    onFailure.accept(e);
                    return;
                }
                if (!frame.rects().isEmpty()) {
                    channel.submit(frame);
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

    /** The channel is closed: silent when the session is ending (interrupted), otherwise one warning. */
    private void closedUnderneath() {
        if (!Thread.currentThread().isInterrupted()) {
            LOG.log(Level.WARNING, "The UDP picture closed while the session runs; capturing stopped");
        }
    }
}

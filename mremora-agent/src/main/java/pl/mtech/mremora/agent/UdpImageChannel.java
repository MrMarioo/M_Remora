package pl.mtech.mremora.agent;

import io.netty.channel.EventLoop;
import io.netty.util.concurrent.ScheduledFuture;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import pl.mtech.mremora.protocol.udp.TileDelivery;
import pl.mtech.mremora.protocol.udp.UdpImageSender;
import pl.mtech.mremora.protocol.udp.UdpPacket;

/**
 * The picture of one v3 session over UDP, on the link's event loop: drives a {@link UdpImageSender} (built over the
 * link's {@link UdpSessionLink#peer() peer}, so probes and fragments share one numbering and are sealed on one
 * thread), sends what it releases to the viewer, polls it again at its {@link UdpImageSender#nextDeadlineNanos
 * deadline} with a one-shot timer and feeds it the viewer's {@code ACK}/{@code PROBE_REPLY} packets.
 *
 * <p>The capture thread talks to it through three calls:
 * <ul>
 *   <li>{@link #awaitAdmits()} parks while the published state of the sender's capture gate says no (republished
 *       after every submit, packet and poll, and kept closed while a handed-over capture is still queued for the
 *       loop, so it never opens on a sender state that misses a submitted capture); advisory only;</li>
 *   <li>{@link #takeForcedIfAdmitted()} makes the real decision on the loop, in one task: if the gate admits a
 *       capture, the forced tiles are taken for it; otherwise nothing is taken. Since the loop runs tasks in order,
 *       the thread's earlier {@link #submit} has been applied by then;</li>
 *   <li>{@link #submit} hands a capture over (async). A capture of a new epoch starts that epoch in the sender first
 *       ({@code newEpoch} before its first submit, in the encoder's order), so no submit is ever ignored; one that
 *       still is, or one the sender rejects, is logged as an ERROR and fails the session, since the encoder already
 *       counts its tiles as sent.</li>
 * </ul>
 * {@link #close()} (any thread; also through the link's close) stops everything and releases a parked capture
 * thread for good. Statistics are published as {@code base} plus this session's counters after every poll.
 *
 * <p>A throw of the sender while polling or while handling a viewer packet is logged (the first of a streak as an
 * ERROR, the rest at DEBUG) and retried: a poll failure re-arms the timer {@value #RETRY_NANOS} ns later, a packet
 * failure still pumps. {@value #MAX_CONSECUTIVE_FAILURES} consecutive failures of the same kind (a success of that
 * kind resets its count) fail the session like a rejected capture.
 */
final class UdpImageChannel {

    private static final Logger LOG = System.getLogger(UdpImageChannel.class.getName());
    /** Shortest delay of the poll timer, so a deadline that is already due cannot spin the loop. */
    static final long MIN_TIMER_NANOS = 50_000L;
    /** Retry delay of the poll timer after polling the sender failed. */
    static final long RETRY_NANOS = 25_000_000L;
    /** Consecutive failures of polling, or of handling viewer packets, that end the session. */
    static final int MAX_CONSECUTIVE_FAILURES = 3;

    /** Tests: fault injection in front of the sender calls; event loop. */
    interface Faults {
        Faults NONE = new Faults() { };

        /** Before {@link UdpImageSender#poll}; a throw counts as a poll failure. */
        default void beforePoll() { }

        /** Before {@link UdpImageSender#onPacket}; a throw counts as a packet failure. */
        default void beforePacket() { }
    }

    private final UdpSessionLink link;
    private final UdpImageSender sender;
    private final EventLoop loop;
    private final UdpImageStats base;
    private final Consumer<UdpImageStats> stats;
    private final Runnable onFailure;

    /** Guards {@link #admits} and the parking of the capture thread; {@link #closed} is written under it. */
    private final Object gate = new Object();
    private boolean admits = true;
    /** Submits handed over and not yet applied on the loop; the published gate stays closed while any is queued. */
    private int pendingSubmits;
    private volatile boolean closed;

    // Event-loop state.
    private ScheduledFuture<?> pollTimer;
    private long pollAtNanos;
    private int ignoredSubmits;
    private boolean failed;
    private int pollFailures;
    private int packetFailures;
    private Faults faults = Faults.NONE;

    /**
     * Event loop: attaches itself to {@code link} ({@link UdpSessionLink#attachImage}).
     *
     * @param sender    built over {@code link.peer()}
     * @param base      cumulative statistics before this session
     * @param stats     gets {@code base} plus this session's counters, on the loop
     * @param onFailure run once on the loop when a capture could not be handed to the sender (the session must end)
     */
    UdpImageChannel(UdpSessionLink link, UdpImageSender sender, UdpImageStats base, Consumer<UdpImageStats> stats,
                    Runnable onFailure) {
        this.link = Objects.requireNonNull(link, "link");
        this.sender = Objects.requireNonNull(sender, "sender");
        this.base = Objects.requireNonNull(base, "base");
        this.stats = Objects.requireNonNull(stats, "stats");
        this.onFailure = Objects.requireNonNull(onFailure, "onFailure");
        this.loop = link.eventLoop();
        if (!loop.inEventLoop()) {
            throw new IllegalStateException("create the image channel on the link's event loop");
        }
        link.attachImage(this);
    }

    /** Any thread: hands one non-empty capture to the sender on the loop; dropped once closed. */
    void submit(TileFrameEncoder.UdpFrame frame) {
        Objects.requireNonNull(frame, "frame");
        if (closed) {
            return;
        }
        synchronized (gate) {
            pendingSubmits++;
            admits = false; // until the loop has applied this capture (and any queued before it) and republished
        }
        if (loop.inEventLoop()) {
            submitOnLoop(frame);
            return;
        }
        try {
            loop.execute(() -> submitOnLoop(frame));
        } catch (RejectedExecutionException e) {
            LOG.log(Level.DEBUG, "Event loop stopped; dropping UDP capture");
            synchronized (gate) {
                pendingSubmits--;
            }
            close(); // the loop is gone, and with it the session: release the capture thread
        }
    }

    /**
     * Any thread: whether the sender's capture gate admitted a capture when last published. Advisory: use
     * {@link #takeForcedIfAdmitted()} for the decision.
     */
    boolean admitsCapture() {
        synchronized (gate) {
            return admits && !closed;
        }
    }

    /**
     * Capture thread: parks until the published gate admits a capture or the channel is closed.
     *
     * @return {@code false} once closed
     */
    boolean awaitAdmits() throws InterruptedException {
        synchronized (gate) {
            while (!admits && !closed) {
                gate.wait();
            }
            return !closed;
        }
    }

    /**
     * Capture thread (never the loop): on the loop, if the gate admits a capture, takes the forced tiles for it.
     * Take only for a capture that will be encoded and submitted right away: taken tiles are not forced again.
     *
     * @return empty when the gate is closed right now or the channel is closed
     */
    Optional<TileDelivery.Forced> takeForcedIfAdmitted() throws InterruptedException {
        if (closed) {
            return Optional.empty();
        }
        try {
            return loop.submit(() -> {
                if (closed) {
                    return Optional.<TileDelivery.Forced>empty();
                }
                boolean open = sender.admitsCapture();
                publishGate(open);
                return open ? Optional.of(sender.takeForced()) : Optional.<TileDelivery.Forced>empty();
            }).get();
        } catch (RejectedExecutionException e) {
            close();
            return Optional.empty();
        } catch (ExecutionException e) {
            throw new IllegalStateException("Checking the UDP capture gate failed", e.getCause());
        }
    }

    /** Whether {@link #close()} was called. Any thread. */
    boolean isClosed() {
        return closed;
    }

    /** Event loop, tests: submits the sender ignored (must stay 0). */
    int ignoredSubmits() {
        return ignoredSubmits;
    }

    /** Event loop, tests: replaces the fault injection ({@link Faults#NONE} by default). */
    void faults(Faults f) {
        faults = Objects.requireNonNull(f, "faults");
    }

    /**
     * Event loop (from the link): an opened {@code ACK} or {@code PROBE_REPLY} of the viewer. Pumps afterwards even
     * when the sender throws.
     */
    void onPacket(UdpPacket p) {
        if (closed) {
            return;
        }
        try {
            faults.beforePacket();
            sender.onPacket(p, System.nanoTime());
            packetFailures = 0;
        } catch (RuntimeException e) {
            if (failedAgain(++packetFailures, "UDP image sender failed on a viewer " + p.type(), e)) {
                return;
            }
        }
        pump();
    }

    /** Stops sending and releases a parked capture thread for good. Idempotent, any thread. */
    void close() {
        synchronized (gate) {
            if (closed) {
                return;
            }
            closed = true;
            gate.notifyAll();
        }
        onLoop(this::cancelTimer);
    }

    private void submitOnLoop(TileFrameEncoder.UdpFrame f) {
        synchronized (gate) {
            pendingSubmits--; // applied (or dropped) now; pump() below republishes the gate
        }
        if (closed) {
            return;
        }
        try {
            if (f.epoch() != sender.epoch()) {
                sender.newEpoch(f.epoch(), f.streamWidth(), f.streamHeight());
            }
            if (!sender.submit(f.epoch(), f.captureSeq(), f.rects(), System.nanoTime())) {
                ignoredSubmits++;
                fail("UDP sender ignored capture " + f.captureSeq() + " of epoch " + f.epoch(), null);
                return;
            }
        } catch (RuntimeException e) {
            fail("UDP sender rejected capture " + f.captureSeq() + " of epoch " + f.epoch(), e);
            return;
        }
        pump();
    }

    /** Event loop: sends what the sender releases now, re-arms the timer, republishes the gate and the stats. */
    private void pump() {
        if (closed) {
            return;
        }
        long now = System.nanoTime();
        try {
            faults.beforePoll();
            List<byte[]> out = sender.poll(now);
            for (byte[] datagram : out) {
                link.sendToViewer(datagram);
            }
            arm(sender.nextDeadlineNanos(now), now);
            publishGate(sender.admitsCapture());
            publishStats();
            pollFailures = 0;
        } catch (RuntimeException e) { // keep the timer alive: one throw here must not stop the picture for good
            if (failedAgain(++pollFailures, "Polling the UDP image sender failed", e)) {
                return;
            }
            publishGate(false); // the sender's state is unknown until a poll succeeds again
            arm(now + RETRY_NANOS, now);
        }
    }

    /**
     * Logs failure number {@code count} of a streak (the first as an ERROR, the rest at DEBUG) and fails the session
     * at {@link #MAX_CONSECUTIVE_FAILURES}.
     *
     * @return whether the session was failed
     */
    private boolean failedAgain(int count, String what, RuntimeException e) {
        if (count >= MAX_CONSECUTIVE_FAILURES) {
            fail(what + " " + count + " times in a row", e);
            return true;
        }
        LOG.log(count == 1 ? Level.ERROR : Level.DEBUG, what + " (retrying)", e);
        return false;
    }

    private void arm(long deadline, long now) {
        if (pollTimer != null && !pollTimer.isDone() && pollAtNanos - deadline <= 0) {
            return; // an earlier wake-up is already armed; it re-arms after polling
        }
        cancelTimer();
        pollAtNanos = deadline;
        long delay = Math.max(MIN_TIMER_NANOS, deadline - now);
        pollTimer = loop.schedule(this::onTimer, delay, TimeUnit.NANOSECONDS);
    }

    private void onTimer() {
        pollTimer = null;
        pump();
    }

    private void cancelTimer() {
        if (pollTimer != null) {
            pollTimer.cancel(false);
            pollTimer = null;
        }
    }

    /** Publishes the sender's gate; it stays closed while a handed-over capture is not applied yet. */
    private void publishGate(boolean senderAdmits) {
        synchronized (gate) {
            boolean open = senderAdmits && pendingSubmits == 0;
            admits = open;
            if (open) {
                gate.notifyAll();
            }
        }
    }

    private void publishStats() {
        stats.accept(new UdpImageStats(
                base.packetsSent() + sender.tilePacketsSent(),
                base.packetsLost() + sender.lostPackets(),
                base.rectsSent() + sender.rectsSubmitted(),
                base.fragmentsSent() + sender.fragmentsQueued(),
                Math.max(base.maxFragmentsPerRect(), sender.maxFragmentsPerRect()),
                base.tilesForced() + sender.tilesForced(),
                sender.rateBitsPerSecond(),
                0,
                0));
    }

    private void fail(String what, RuntimeException cause) {
        LOG.log(Level.ERROR, what + "; ending the session (its tiles would stay stale)", cause);
        if (failed) {
            return;
        }
        failed = true;
        close();
        onFailure.run();
    }

    private void onLoop(Runnable task) {
        if (loop.inEventLoop()) {
            task.run();
            return;
        }
        try {
            loop.execute(task);
        } catch (RejectedExecutionException e) {
            LOG.log(Level.DEBUG, "Event loop stopped; dropping UDP image task");
        }
    }
}

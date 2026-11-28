package pl.mtech.mremora.protocol.udp;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Agent side: delivery state of the tiles sent over UDP. Each tile remembers the {@code captureSeq} of its last sent
 * version; each registered rectangle is tracked until it ends. A rectangle ends as:
 * <ul>
 *   <li><b>done</b> – the viewer applied or skipped it;</li>
 *   <li><b>lost</b> – one of its packets was declared lost;</li>
 *   <li><b>dropped</b> – the viewer reported it as dropped;</li>
 *   <li><b>timed out</b> – all its packets acknowledged, but neither "done" nor "dropped" while the viewer made no
 *       progress for {@link #CONFIRM_TIMEOUT_NANOS}: the timer runs from the later of the acknowledgement of its last
 *       packet and the viewer's last progress ({@link #viewerProgress}, an ACK that ended a rectangle). A viewer that
 *       is slowly working through a backlog therefore causes no resends; one that lost a rectangle without reporting
 *       it gets it forced once it stops making progress. Deliberately not the PTO: confirmation waits for decoding
 *       and the UI thread. Cap: a rectangle <i>overtaken</i> by a newer one the viewer reported done (applied in
 *       order after it) is lost {@link #CONFIRM_CAP_NANOS} (10 s) after it was first seen overtaken, even while the
 *       viewer keeps making progress – this covers a dropped report lost in every ACK that carried it.</li>
 * </ul>
 * A lost, dropped or timed-out rectangle forces the tiles whose sent version is still its {@code captureSeq} – a
 * newer version already on its way supersedes it. The end state is final: later events for the rectangle are ignored.
 * Ending releases the rectangle's bytes from {@link #unconfirmedBytes()}. A new epoch forgets everything of the old
 * one; {@code rectId}s keep growing across epochs, so late events of old rectangles cannot hit new ones.
 * "Done" can arrive slightly out of apply order: the viewer reports a rectangle it skipped entirely (nothing newer to
 * paint) at once on its decoder thread, ahead of rectangles still waiting for the UI. That is harmless: it can only
 * mark those older rectangles overtaken a little early, and their cap ({@link #CONFIRM_CAP_NANOS}) is far longer than
 * the viewer's bounded UI backlog.
 * Time is passed in as nanoseconds and compared overflow-safe. Not thread-safe.
 */
public final class TileDelivery {

    /**
     * How long a fully acknowledged rectangle may stay unconfirmed without the viewer making progress (counted from
     * the later of the acknowledgement and the last {@link #viewerProgress}). An overtaken rectangle is additionally
     * capped at {@link #CONFIRM_CAP_NANOS} (10 s) from when it was first seen overtaken.
     */
    public static final long CONFIRM_TIMEOUT_NANOS = 2_000_000_000L;
    /**
     * Cap for an <i>overtaken</i> rectangle: fully acknowledged, unresolved, and a newer one (higher rectId) was
     * already reported done. Only "done" counts: it comes from the apply stage, in apply order, whereas "dropped" is
     * often a receive-time report (assembly expiry, eviction) that can overtake rects still queued for apply. It is
     * lost this long after it was first seen overtaken, even while the viewer
     * keeps making progress (e.g. its dropped report was lost in every ACK that carried it). A viewer applying a FIFO
     * backlog finishes rects in order, so a long backlog is never overtaken and never hits the cap.
     */
    public static final long CONFIRM_CAP_NANOS = 5 * CONFIRM_TIMEOUT_NANOS;

    private static final long NEVER_SENT = Long.MIN_VALUE;

    /**
     * Forced tiles taken by {@link #takeForced()}.
     *
     * @param epoch epoch the tile indices belong to
     * @param tiles tiles to send again in their current version
     */
    public record Forced(int epoch, BitSet tiles) {}

    private static final class Rect {
        final long captureSeq;
        final BitSet tiles;
        final int bytes;
        final Set<Long> packets = new HashSet<>();
        int unacked;
        boolean allAcked;
        long allAckedNanos;
        boolean overtaken;
        long overtakenNanos;

        Rect(long captureSeq, BitSet tiles, int bytes) {
            this.captureSeq = captureSeq;
            this.tiles = tiles;
            this.bytes = bytes;
        }
    }

    private final Map<Long, Rect> rects = new LinkedHashMap<>();
    /** Packet number → rectId, only for packets of live rectangles that are not acknowledged yet. */
    private final Map<Long, Long> packetRect = new HashMap<>();
    private int epoch = -1;
    private TileGrid grid;
    private long[] sentSeq = new long[0];
    private BitSet forced = new BitSet();
    private long unconfirmedBytes;
    private long nextRectId = 1;
    /** captureSeq → number of its rectangles not ended yet. */
    private final Map<Long, Integer> unresolvedPerCapture = new HashMap<>();
    private boolean anyProgress;
    private long lastProgressNanos;
    /** Highest rectId the viewer reported done (0 = none); "dropped" does not count (see CONFIRM_CAP_NANOS). */
    private long maxEndedByViewer;

    /** Starts {@code epoch} with a new grid: all state of the previous epoch is forgotten and its bytes released. */
    public void newEpoch(int epoch, TileGrid grid) {
        this.epoch = epoch;
        this.grid = grid;
        this.sentSeq = new long[grid.count()];
        Arrays.fill(sentSeq, NEVER_SENT);
        this.forced = new BitSet();
        rects.clear();
        packetRect.clear();
        unresolvedPerCapture.clear();
        unconfirmedBytes = 0;
    }

    /**
     * Registers a rectangle about to be sent: its tiles' sent version becomes {@code captureSeq} and any pending force
     * on them is cleared (this rectangle carries their current version).
     *
     * @return the new {@code rectId} (from 1, growing for the whole session)
     * @throws IllegalStateException    before the first {@link #newEpoch}
     * @throws IllegalArgumentException if {@code epoch} is not the current epoch
     */
    public long register(int epoch, long captureSeq, BitSet tiles, int bytes) {
        if (grid == null) {
            throw new IllegalStateException("no epoch started");
        }
        if (epoch != this.epoch) {
            throw new IllegalArgumentException("epoch " + epoch + " is not current " + this.epoch);
        }
        BitSet copy = (BitSet) tiles.clone();
        for (int t = copy.nextSetBit(0); t >= 0; t = copy.nextSetBit(t + 1)) {
            if (t >= sentSeq.length) {
                throw new IllegalArgumentException("tile " + t + " outside grid of " + sentSeq.length);
            }
            sentSeq[t] = captureSeq;
        }
        forced.andNot(copy);
        long rectId = nextRectId++;
        rects.put(rectId, new Rect(captureSeq, copy, bytes));
        unresolvedPerCapture.merge(captureSeq, 1, Integer::sum);
        unconfirmedBytes += bytes;
        return rectId;
    }

    /** Records that packet {@code number} carries a fragment of {@code rectId}; ignored for an ended rectangle. */
    public void packetSent(long rectId, long number) {
        Rect r = rects.get(rectId);
        if (r == null || !r.packets.add(number)) {
            return;
        }
        packetRect.put(number, rectId);
        r.unacked++;
        r.allAcked = false;
    }

    /**
     * Records acknowledged packets. A rectangle whose every sent packet is acknowledged starts its confirm timeout at
     * {@code nowNanos}. Unknown numbers are ignored.
     */
    public void packetsAcked(List<Long> numbers, long nowNanos) {
        for (long n : numbers) {
            Long rectId = packetRect.remove(n);
            if (rectId == null) {
                continue;
            }
            Rect r = rects.get(rectId);
            if (r != null && --r.unacked == 0) {
                r.allAcked = true;
                r.allAckedNanos = nowNanos;
            }
        }
    }

    /** Packet {@code number} was declared lost: its rectangle is lost. Unknown numbers are ignored. */
    public void packetLost(long number) {
        Long rectId = packetRect.get(number);
        if (rectId != null) {
            fail(rectId);
        }
    }

    /**
     * The viewer applied or skipped {@code rectId}; ignored for an ended or unknown rectangle.
     *
     * @return whether this ended a rectangle (new progress of the viewer)
     */
    public boolean done(long rectId) {
        Rect r = rects.remove(rectId);
        if (r == null) {
            return false;
        }
        release(r);
        maxEndedByViewer = Math.max(maxEndedByViewer, rectId);
        return true;
    }

    /**
     * The viewer dropped {@code rectId}: its current tiles are forced. Ignored for an ended or unknown rectangle.
     *
     * @return whether this ended a rectangle (new progress of the viewer)
     */
    public boolean dropped(long rectId) {
        return fail(rectId); // not an overtaking report: see CONFIRM_CAP_NANOS
    }

    /**
     * The viewer made progress at {@code nowNanos}: an ACK reported a newly finished or dropped rectangle. Restarts
     * the confirm timeout of every rectangle still waiting.
     */
    public void viewerProgress(long nowNanos) {
        anyProgress = true;
        lastProgressNanos = nowNanos;
    }

    /**
     * Treats as lost every rectangle whose packets are all acknowledged and that got no "done"/"dropped" while the
     * viewer made no progress for {@link #CONFIRM_TIMEOUT_NANOS} (counted from the later of the acknowledgement and
     * the last {@link #viewerProgress}), or that has been overtaken for {@link #CONFIRM_CAP_NANOS} whatever the
     * progress (overtaken from the first check that sees a newer rectId ended by the viewer).
     *
     * @return the number of rectangles that timed out
     */
    public int checkConfirmTimeout(long nowNanos) {
        List<Long> expired = new ArrayList<>();
        for (Map.Entry<Long, Rect> e : rects.entrySet()) {
            Rect r = e.getValue();
            if (!r.allAcked) {
                continue;
            }
            if (!r.overtaken && e.getKey() < maxEndedByViewer) {
                r.overtaken = true;
                r.overtakenNanos = nowNanos;
            }
            long since = anyProgress && lastProgressNanos - r.allAckedNanos > 0 ? lastProgressNanos : r.allAckedNanos;
            boolean capped = r.overtaken && nowNanos - r.overtakenNanos - CONFIRM_CAP_NANOS >= 0;
            if (nowNanos - since - CONFIRM_TIMEOUT_NANOS >= 0 || capped) {
                expired.add(e.getKey());
            }
        }
        for (long rectId : expired) {
            fail(rectId);
        }
        return expired.size();
    }

    /** Number of captures that still have a rectangle not ended (not done, lost, dropped or timed out). */
    public int unresolvedCaptures() {
        return unresolvedPerCapture.size();
    }

    /** Number of rectangles registered in the current epoch that have not ended. */
    public int liveRects() {
        return rects.size();
    }

    /** Whether {@code rectId} is registered in the current epoch and has not ended. */
    public boolean isLive(long rectId) {
        return rects.containsKey(rectId);
    }

    /** Returns the forced tiles of the current epoch and clears them. */
    public Forced takeForced() {
        BitSet taken = forced;
        forced = new BitSet();
        return new Forced(epoch, taken);
    }

    /** Whether forced tiles are waiting for {@link #takeForced()} (does not take them). */
    public boolean hasForced() {
        return !forced.isEmpty();
    }

    /** Total size of the rectangles registered and not yet ended. */
    public long unconfirmedBytes() {
        return unconfirmedBytes;
    }

    /** Current epoch, -1 before the first {@link #newEpoch}. */
    public int epoch() {
        return epoch;
    }

    private boolean fail(long rectId) {
        Rect r = rects.remove(rectId);
        if (r == null) {
            return false;
        }
        for (int t = r.tiles.nextSetBit(0); t >= 0; t = r.tiles.nextSetBit(t + 1)) {
            if (sentSeq[t] == r.captureSeq) {
                forced.set(t);
            }
        }
        release(r);
        return true;
    }

    private void release(Rect r) {
        unconfirmedBytes -= r.bytes;
        unresolvedPerCapture.computeIfPresent(r.captureSeq, (seq, n) -> n > 1 ? n - 1 : null);
        for (long n : r.packets) {
            packetRect.remove(n);
        }
    }
}

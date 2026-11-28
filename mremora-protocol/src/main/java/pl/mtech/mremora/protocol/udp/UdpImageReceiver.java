package pl.mtech.mremora.protocol.udp;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import pl.mtech.mremora.protocol.udp.UdpPacket.Type;

/**
 * Viewer side of the image stream over UDP: assembles {@code TILE} fragments ({@link RectAssembler}), remembers
 * which packets arrived and which rectangles ended, and builds the {@code ACK}s. Applying a rectangle (and the tile
 * versions, {@link TileVersions}) is the caller's job; it reports the outcome with {@link #applied} or
 * {@link #dropped}. The caller sends whatever {@link #poll} returns, calls {@code poll} again at
 * {@link #nextDeadlineNanos}, and answers the agent's {@code PROBE}s itself ({@link UdpPeer#answer}).
 *
 * <p>ACK cadence: after every {@value #ACK_EVERY_PACKETS} packets that need one ({@code TILE}, {@code PROBE}), or at
 * the latest {@link #MAX_ACK_DELAY_NANOS} after the first unacknowledged one; a rectangle that ended (applied,
 * dropped, expired) also makes an ACK due within {@link #MAX_ACK_DELAY_NANOS}. Each ACK carries the 32 newest packet
 * ranges and the 32 newest ranges of finished rectIds; a dropped rectId is repeated in {@value #DROPPED_REPEATS}
 * consecutive ACKs. Single-threaded; time only from {@code nowNanos}, compared overflow-safe.
 */
public final class UdpImageReceiver {

    static final int ACK_EVERY_PACKETS = 2;
    static final long MAX_ACK_DELAY_NANOS = RttEstimator.MAX_ACK_DELAY_NANOS;
    static final int DROPPED_REPEATS = 3;
    /** Range memory keeps only numbers this close to the largest one. */
    static final long REMEMBER = 4096;
    /** How often {@link #nextDeadlineNanos} wakes the caller to expire incomplete rectangles. */
    static final long EXPIRE_CHECK_NANOS = 100_000_000L;
    /** Wake-up interval when nothing at all is pending. */
    static final long IDLE_NANOS = 1_000_000_000L;

    private static final long NANOS_PER_MS = 1_000_000L;

    private final UdpPeer peer;
    private final RectAssembler assembler = new RectAssembler(RectAssembler.EXPIRE_NANOS, RectAssembler.MAX_BYTES);
    private final RangeSet packets = new RangeSet();
    private final RangeSet doneRects = new RangeSet();
    /** Dropped rectId → ACKs that still have to carry it. */
    private final Map<Long, Integer> droppedRepeats = new LinkedHashMap<>();

    private int unacked;
    private long firstUnackedNanos;
    private long largestNumber = -1;
    private long largestReceivedNanos;
    /** A rectangle ended since the last ACK; its 25 ms timer starts at {@link #finishNanos} once stamped. */
    private boolean finishPending;
    private boolean finishStamped;
    private long finishNanos;

    public UdpImageReceiver(UdpPeer peer) {
        this.peer = Objects.requireNonNull(peer, "peer");
    }

    /**
     * Handles an opened packet of the agent. {@code TILE} and {@code PROBE} are recorded for the next ACK;
     * a {@code TILE} fragment goes to the assembler.
     *
     * @return the rectangle this fragment completed
     */
    public Optional<CompleteRect> onPacket(UdpPacket p, long nowNanos) {
        if (p.type() != Type.TILE && p.type() != Type.PROBE) {
            return Optional.empty();
        }
        packets.add(p.number());
        packets.removeBelow(packets.max() - REMEMBER);
        if (unacked == 0) {
            firstUnackedNanos = nowNanos;
        }
        unacked++;
        if (p.number() > largestNumber) {
            largestNumber = p.number();
            largestReceivedNanos = nowNanos;
        }
        if (p.type() != Type.TILE) {
            return Optional.empty();
        }
        Optional<TileFragment> fragment = TileFragment.decode(p.payload());
        if (fragment.isEmpty()) {
            return Optional.empty();
        }
        Optional<CompleteRect> rect = assembler.add(fragment.get(), nowNanos);
        collectDropped(nowNanos);
        return rect;
    }

    /**
     * The caller applied {@code rectId}, or skipped it because all its tiles already carry a newer version.
     * The ACK it makes due is timed from the next {@link #poll} or {@link #nextDeadlineNanos} call.
     */
    public void applied(long rectId) {
        doneRects.add(rectId);
        doneRects.removeBelow(doneRects.max() - REMEMBER);
        finishPending = true;
    }

    /**
     * The caller discarded {@code rectId} (old epoch, decoding error, failed checks). Timed like {@link #applied}.
     */
    public void dropped(long rectId) {
        droppedRepeats.put(rectId, DROPPED_REPEATS);
        finishPending = true;
    }

    /** The ACK, if one is due; also expires incomplete rectangles (reported as dropped). */
    public List<byte[]> poll(long nowNanos) {
        assembler.expire(nowNanos).forEach(id -> droppedRepeats.put(id, DROPPED_REPEATS));
        if (!droppedRepeats.isEmpty()) {
            finishPending = true;
        }
        collectDropped(nowNanos);
        stampFinish(nowNanos);
        if (!ackDue(nowNanos)) {
            return List.of();
        }
        return List.of(peer.seal(Type.ACK, buildAck(nowNanos).encode()));
    }

    /**
     * When {@link #poll} has something to do: an ACK becoming due, an expiry check while rectangles are incomplete
     * ({@value #EXPIRE_CHECK_NANOS} ns granularity), or an idle wake-up a second later.
     */
    public long nextDeadlineNanos(long nowNanos) {
        stampFinish(nowNanos);
        if (ackDue(nowNanos)) {
            return nowNanos;
        }
        long delta = assembler.heldBytes() > 0 ? EXPIRE_CHECK_NANOS : IDLE_NANOS;
        if (unacked > 0) {
            delta = Math.min(delta, Math.max(0, firstUnackedNanos + MAX_ACK_DELAY_NANOS - nowNanos));
        }
        if (finishStamped) {
            delta = Math.min(delta, Math.max(0, finishNanos + MAX_ACK_DELAY_NANOS - nowNanos));
        }
        return nowNanos + delta;
    }

    /** The newest epoch seen in a fragment, -1 before the first. */
    public int epoch() {
        return assembler.currentEpoch();
    }

    /** Bytes buffered for incomplete rectangles. */
    public int heldBytes() {
        return assembler.heldBytes();
    }

    private void collectDropped(long nowNanos) {
        List<Long> ids = assembler.takeDropped();
        if (!ids.isEmpty()) {
            ids.forEach(id -> droppedRepeats.put(id, DROPPED_REPEATS));
            finishPending = true;
            stampFinish(nowNanos);
        }
    }

    private void stampFinish(long nowNanos) {
        if (finishPending && !finishStamped) {
            finishStamped = true;
            finishNanos = nowNanos;
        }
    }

    private boolean ackDue(long nowNanos) {
        if (unacked >= ACK_EVERY_PACKETS) {
            return true;
        }
        if (unacked > 0 && nowNanos - firstUnackedNanos - MAX_ACK_DELAY_NANOS >= 0) {
            return true;
        }
        return finishStamped && nowNanos - finishNanos - MAX_ACK_DELAY_NANOS >= 0;
    }

    private AckFrame buildAck(long nowNanos) {
        long delayMs = largestNumber < 0 ? 0 : Math.max(0, nowNanos - largestReceivedNanos) / NANOS_PER_MS;
        List<Long> dropped = new ArrayList<>();
        for (Iterator<Map.Entry<Long, Integer>> it = droppedRepeats.entrySet().iterator(); it.hasNext()
                && dropped.size() < AckFrame.MAX_DROPPED; ) {
            Map.Entry<Long, Integer> e = it.next();
            dropped.add(e.getKey());
            if (e.getValue() <= 1) {
                it.remove();
            } else {
                e.setValue(e.getValue() - 1);
            }
        }
        unacked = 0;
        finishPending = !droppedRepeats.isEmpty();
        finishStamped = false;
        stampFinish(nowNanos);
        return new AckFrame((int) Math.min(delayMs, 0xFFFF), packets.newest(AckFrame.MAX_RANGES),
                doneRects.newest(AckFrame.MAX_RANGES), dropped);
    }
}

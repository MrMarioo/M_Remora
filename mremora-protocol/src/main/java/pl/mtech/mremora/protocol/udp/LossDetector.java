package pl.mtech.mremora.protocol.udp;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.TreeMap;

/**
 * Sender-side loss detection, simplified RFC 9002 section 6: a packet is lost when at least 3 packets sent after it
 * were acknowledged, or when a newer packet was acknowledged and more than 9/8 of the RTT passed since it was sent. A
 * probe timeout (PTO) with exponential back-off covers lost tails. Only in-flight packets are stored; an acknowledged
 * or lost packet is forgotten. ACK ranges are never iterated over their span, only over in-flight entries inside them.
 * Time is passed in as nanoseconds and compared overflow-safe. Not thread-safe.
 */
public final class LossDetector {

    private static final int PACKET_THRESHOLD = 3;
    private static final long MAX_PTO_NANOS = 2_000_000_000L;
    private static final long TIMER_GRANULARITY_NANOS = 1_000_000L;

    private record Sent(long sentNanos, int bytes, boolean ackEliciting, boolean pacerLimited) {}

    /**
     * Result of {@link #onAck}.
     *
     * @param acked                  newly acknowledged packet numbers
     * @param ackedBytes             their total size
     * @param ackedPacerLimitedBytes size of those sent while the pacer was the limit
     * @param lost                   packets declared lost because of this ACK
     * @param rttSampled             whether the RTT estimator got a new sample
     */
    public record AckOutcome(List<Long> acked, long ackedBytes, long ackedPacerLimitedBytes, List<Long> lost,
                             boolean rttSampled) {}

    private final RttEstimator rtt;
    private final TreeMap<Long, Sent> inFlight = new TreeMap<>();
    private long bytesInFlight;
    private int ackElicitingInFlight;
    private long lastAckElicitingSent;
    private long largestAcked = -1;
    private int ptoCount;

    public LossDetector(RttEstimator rtt) {
        this.rtt = rtt;
    }

    /** Registers a sent packet. */
    public void sent(long number, long sentNanos, int bytes, boolean ackEliciting, boolean pacerLimited) {
        if (inFlight.containsKey(number)) {
            throw new IllegalArgumentException("packet number reused: " + number);
        }
        inFlight.put(number, new Sent(sentNanos, bytes, ackEliciting, pacerLimited));
        bytesInFlight += bytes;
        if (ackEliciting) {
            ackElicitingInFlight++;
            lastAckElicitingSent = sentNanos;
        }
    }

    /**
     * Processes the packet ranges of an ACK. Numbers not in flight (duplicates, unknown) are ignored. The ACK delay is
     * clamped to {@link RttEstimator#MAX_ACK_DELAY_NANOS}.
     */
    public AckOutcome onAck(List<RangeSet.Range> ranges, long ackDelayNanos, long nowNanos) {
        List<Long> acked = new ArrayList<>();
        java.util.Set<Long> seen = new java.util.HashSet<>();
        long ackedBytes = 0;
        long pacerLimitedBytes = 0;
        long largestNew = -1;
        Sent largestNewSent = null;
        boolean anyEliciting = false;
        for (RangeSet.Range r : ranges) {
            for (Map.Entry<Long, Sent> e : inFlight.subMap(r.from(), true, r.to(), true).entrySet()) {
                if (!seen.add(e.getKey())) {
                    continue; // overlapping ranges
                }
                acked.add(e.getKey());
                Sent s = e.getValue();
                ackedBytes += s.bytes();
                if (s.pacerLimited()) {
                    pacerLimitedBytes += s.bytes();
                }
                anyEliciting |= s.ackEliciting();
                if (e.getKey() > largestNew) {
                    largestNew = e.getKey();
                    largestNewSent = s;
                }
            }
        }
        for (long n : acked) {
            remove(n);
        }
        boolean sampled = false;
        if (!acked.isEmpty()) {
            ptoCount = 0;
            boolean newLargest = largestNew > largestAcked;
            if (newLargest) {
                largestAcked = largestNew;
            }
            if (largestNew == largestAcked && anyEliciting) {
                long delay = Math.min(Math.max(ackDelayNanos, 0), RttEstimator.MAX_ACK_DELAY_NANOS);
                rtt.sample(Math.max(nowNanos - largestNewSent.sentNanos(), 0), delay);
                sampled = true;
            }
        }
        List<Long> lost = acked.isEmpty() ? List.of() : detect(nowNanos);
        return new AckOutcome(acked, ackedBytes, pacerLimitedBytes, lost, sampled);
    }

    /** Declares lost (and forgets) the packets older than an acknowledged one whose time threshold has passed. */
    public List<Long> detectByTime(long nowNanos) {
        return detect(nowNanos);
    }

    /** When the oldest not-yet-lost packet below the largest acknowledged one crosses the time threshold. */
    public OptionalLong lossTimeDeadline() {
        if (largestAcked < 0) {
            return OptionalLong.empty();
        }
        long delay = lossDelay();
        OptionalLong best = OptionalLong.empty();
        for (Sent s : inFlight.headMap(largestAcked, false).values()) {
            long t = s.sentNanos() + delay;
            if (best.isEmpty() || t - best.getAsLong() < 0) {
                best = OptionalLong.of(t);
            }
        }
        return best;
    }

    /**
     * When the PTO fires: the last ack-eliciting send plus the PTO, doubled per fired PTO, at most 2 s after the send.
     * {@link Long#MAX_VALUE} when no ack-eliciting packet is in flight (check {@link #anyAckElicitingInFlight()}).
     */
    public long ptoDeadline() {
        if (ackElicitingInFlight == 0) {
            return Long.MAX_VALUE;
        }
        long base = rtt.pto();
        long pto = ptoCount >= 20 || base > (MAX_PTO_NANOS >> ptoCount) ? MAX_PTO_NANOS : base << ptoCount;
        return lastAckElicitingSent + Math.min(pto, MAX_PTO_NANOS);
    }

    /** A PTO fired without an ACK: the next one is twice as far away. */
    public void ptoFired() {
        if (ptoCount < 20) {
            ptoCount++;
        }
    }

    public long bytesInFlight() {
        return bytesInFlight;
    }

    public boolean anyAckElicitingInFlight() {
        return ackElicitingInFlight > 0;
    }

    private long lossDelay() {
        long base = rtt.hasSample() ? Math.max(rtt.smoothed(), rtt.latest()) : RttEstimator.INITIAL_PTO_NANOS;
        return Math.max(base / 8 * 9 + base % 8 * 9 / 8, TIMER_GRANULARITY_NANOS);
    }

    private List<Long> detect(long nowNanos) {
        if (largestAcked < 0) {
            return List.of();
        }
        long delay = lossDelay();
        List<Long> lost = new ArrayList<>();
        for (Map.Entry<Long, Sent> e : inFlight.headMap(largestAcked, false).entrySet()) {
            boolean byCount = largestAcked - e.getKey() >= PACKET_THRESHOLD;
            boolean byTime = nowNanos - (e.getValue().sentNanos() + delay) >= 0;
            if (byCount || byTime) {
                lost.add(e.getKey());
            }
        }
        for (long n : lost) {
            remove(n);
        }
        return lost;
    }

    private void remove(long number) {
        Sent s = inFlight.remove(number);
        bytesInFlight -= s.bytes();
        if (s.ackEliciting()) {
            ackElicitingInFlight--;
        }
    }
}

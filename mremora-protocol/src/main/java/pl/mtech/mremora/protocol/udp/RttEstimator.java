package pl.mtech.mremora.protocol.udp;

/**
 * Round-trip time estimator, simplified RFC 9002 section 5: smoothed RTT, variance, latest and minimum sample, and the
 * probe timeout (PTO) derived from them. All values are nanoseconds. Not thread-safe.
 */
public final class RttEstimator {

    /** Receiver's maximum ACK delay assumed in the PTO and clamped from reported ACK delays: 25 ms. */
    public static final long MAX_ACK_DELAY_NANOS = 25_000_000L;

    /** PTO used before the first RTT sample: 300 ms. */
    public static final long INITIAL_PTO_NANOS = 300_000_000L;

    private static final long MIN_VARIANCE_TERM_NANOS = 1_000_000L;

    private boolean hasSample;
    private long smoothed;
    private long variance;
    private long latest;
    private long min = Long.MAX_VALUE;

    /**
     * Adds a sample.
     *
     * @param rttNanos      measured time from sending to receiving the ACK
     * @param ackDelayNanos delay the receiver reported; subtracted only when the result stays at or above the minimum RTT
     */
    public void sample(long rttNanos, long ackDelayNanos) {
        if (rttNanos < 0) {
            rttNanos = 0;
        }
        latest = rttNanos;
        min = Math.min(min, rttNanos);
        long adjusted = rttNanos;
        if (hasSample && ackDelayNanos > 0 && rttNanos - ackDelayNanos >= min) {
            adjusted = rttNanos - ackDelayNanos;
        }
        if (!hasSample) {
            smoothed = adjusted;
            variance = adjusted / 2;
            hasSample = true;
        } else {
            variance = (3 * variance + Math.abs(smoothed - adjusted)) / 4;
            smoothed = (7 * smoothed + adjusted) / 8;
        }
    }

    public boolean hasSample() {
        return hasSample;
    }

    /** Smoothed RTT; 0 before the first sample. */
    public long smoothed() {
        return smoothed;
    }

    public long variance() {
        return variance;
    }

    /** The most recent raw sample. */
    public long latest() {
        return latest;
    }

    /** Smallest raw sample; 0 before the first sample. */
    public long min() {
        return hasSample ? min : 0;
    }

    /** {@code sRTT + max(4 * RTTvar, 1 ms) + 25 ms}; 300 ms before the first sample. */
    public long pto() {
        if (!hasSample) {
            return INITIAL_PTO_NANOS;
        }
        return smoothed + Math.max(4 * variance, MIN_VARIANCE_TERM_NANOS) + MAX_ACK_DELAY_NANOS;
    }
}

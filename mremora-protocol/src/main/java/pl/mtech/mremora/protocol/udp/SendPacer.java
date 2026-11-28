package pl.mtech.mremora.protocol.udp;

/**
 * Token-bucket send pacer with AIMD rate control. Single-threaded; time is passed in as {@code nowNanos} and compared
 * overflow-safe. The rate is in bits per second.
 *
 * <p>Slow start (rate doubles per RTT) lasts until the first loss, then growth is additive ({@link #ADD_BPS} per RTT)
 * and a loss halves the rate at most once per RTT. Growth is driven only by acked bytes of sends that were limited by
 * the pacer, so an idle desktop never inflates the rate. "Once per RTT" is approximated by "acked bytes in an RTT
 * are about rate x RTT".
 */
final class SendPacer {

    static final long START_BPS = 5_000_000L;
    static final long MIN_BPS = 500_000L;
    static final long MAX_BPS = 200_000_000L;
    static final long ADD_BPS = 500_000L;
    static final int BURST_BYTES = 16 * 1024;

    private static final double NANOS_PER_SECOND = 1_000_000_000d;

    private long rate = START_BPS;
    private double tokens = BURST_BYTES;
    private long lastRefillNanos;
    private boolean slowStart = true;
    private boolean everCut;
    private long lastCutNanos;

    SendPacer(long nowNanos) {
        this.lastRefillNanos = nowNanos;
    }

    long rateBitsPerSecond() {
        return rate;
    }

    /** Nanoseconds to wait before {@code bytes} may be sent; 0 means now. */
    long nanosUntilAllowed(int bytes, long nowNanos) {
        refill(nowNanos);
        double need = Math.min(bytes, BURST_BYTES);
        if (tokens >= need) {
            return 0;
        }
        return Math.max(1L, (long) Math.ceil((need - tokens) * 8d * NANOS_PER_SECOND / rate));
    }

    /** Takes {@code bytes} from the bucket (may go into debt when sending a packet larger than the balance). */
    void consumed(int bytes, long nowNanos) {
        refill(nowNanos);
        tokens = Math.max(-BURST_BYTES, tokens - bytes);
    }

    /**
     * Reports acked bytes of packets that were sent while the pacer was the limit.
     *
     * @param pacerLimitedBytes acked bytes of pacer-limited sends (0 = nothing to learn from)
     * @param rttNanos current RTT estimate
     */
    void onAcked(long pacerLimitedBytes, long rttNanos, long nowNanos) {
        if (pacerLimitedBytes <= 0 || rttNanos <= 0) {
            return;
        }
        refill(nowNanos);
        double bits = pacerLimitedBytes * 8d;
        double newRate;
        if (slowStart) {
            newRate = rate + bits * NANOS_PER_SECOND / rttNanos; // acked bits per RTT = rate -> doubles
        } else {
            // +ADD_BPS per (rate x RTT) bits acked
            double perRttBits = rate * (rttNanos / NANOS_PER_SECOND);
            newRate = rate + ADD_BPS * Math.min(bits / Math.max(perRttBits, 1d), 1d);
        }
        rate = clamp(newRate);
    }

    /**
     * Reports a lost packet.
     *
     * @param lostPacketSentNanos when the lost packet was sent; a packet sent before the last cut is ignored
     */
    void onLoss(long lostPacketSentNanos, long nowNanos) {
        slowStart = false;
        if (everCut && lostPacketSentNanos - lastCutNanos <= 0) {
            return;
        }
        refill(nowNanos);
        rate = clamp(rate / 2d);
        everCut = true;
        lastCutNanos = nowNanos;
    }

    private void refill(long nowNanos) {
        long dt = nowNanos - lastRefillNanos;
        if (dt <= 0) {
            return;
        }
        lastRefillNanos = nowNanos;
        tokens = Math.min(BURST_BYTES, tokens + dt * (rate / 8d) / NANOS_PER_SECOND);
    }

    private static long clamp(double bps) {
        return (long) Math.max(MIN_BPS, Math.min(MAX_BPS, bps));
    }
}

package pl.mtech.mremora.protocol.udp;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SendPacerTest {

    private static final long MS = 1_000_000L;
    private static final long RTT = 100 * MS;

    /** Bytes acked in one RTT at the given rate. */
    private static long perRtt(long bps) {
        return bps / 8 / 10;
    }

    @Test
    void startsAtFiveMbit() {
        assertThat(new SendPacer(0).rateBitsPerSecond()).isEqualTo(5_000_000L);
    }

    @Test
    void tokenBucketSpacing() {
        SendPacer p = new SendPacer(0);
        long now = 0;
        int sent = 0;
        while (p.nanosUntilAllowed(1200, now) == 0) {
            p.consumed(1200, now);
            sent += 1200;
        }
        assertThat(sent).isGreaterThanOrEqualTo(SendPacer.BURST_BYTES - 1200);
        assertThat(sent).isLessThanOrEqualTo(SendPacer.BURST_BYTES);
        // after the burst the spacing is 1200 B * 8 / 5 Mbit/s = 1.92 ms
        long wait = p.nanosUntilAllowed(1200, now);
        assertThat(wait).isBetween(1L, 1_920_000L);
        now += wait;
        assertThat(p.nanosUntilAllowed(1200, now)).isZero();
        p.consumed(1200, now);
        assertThat(p.nanosUntilAllowed(1200, now)).isBetween(1_900_000L, 1_920_000L);
    }

    @Test
    void slowStartDoublesPerRtt() {
        SendPacer p = new SendPacer(0);
        p.onAcked(perRtt(5_000_000), RTT, RTT);
        assertThat(p.rateBitsPerSecond()).isEqualTo(10_000_000L);
        p.onAcked(perRtt(10_000_000), RTT, 2 * RTT);
        assertThat(p.rateBitsPerSecond()).isEqualTo(20_000_000L);
    }

    @Test
    void lossHalves() {
        SendPacer p = new SendPacer(0);
        p.onLoss(0, RTT);
        assertThat(p.rateBitsPerSecond()).isEqualTo(2_500_000L);
    }

    @Test
    void lossOncePerRtt() {
        SendPacer p = new SendPacer(0);
        p.onLoss(10 * MS, 100 * MS);
        p.onLoss(20 * MS, 101 * MS);
        p.onLoss(50 * MS, 102 * MS);
        assertThat(p.rateBitsPerSecond()).isEqualTo(2_500_000L);
        // a packet sent after the cut cuts again
        p.onLoss(150 * MS, 250 * MS);
        assertThat(p.rateBitsPerSecond()).isEqualTo(1_250_000L);
    }

    @Test
    void additiveAfterFirstLoss() {
        SendPacer p = new SendPacer(0);
        p.onLoss(0, RTT);
        long r = p.rateBitsPerSecond();
        p.onAcked(perRtt(r), RTT, 2 * RTT);
        assertThat(p.rateBitsPerSecond()).isEqualTo(r + SendPacer.ADD_BPS);
    }

    @Test
    void clampedMinMax() {
        SendPacer p = new SendPacer(0);
        long now = 0;
        for (int i = 0; i < 20; i++) {
            now += RTT;
            p.onLoss(now - 1, now);
        }
        assertThat(p.rateBitsPerSecond()).isEqualTo(SendPacer.MIN_BPS);
        SendPacer q = new SendPacer(0);
        for (int i = 0; i < 20; i++) {
            q.onAcked(q.rateBitsPerSecond() / 8, 1_000_000_000L, (i + 1) * 1_000_000_000L);
        }
        assertThat(q.rateBitsPerSecond()).isEqualTo(SendPacer.MAX_BPS);
    }

    @Test
    void idleDoesNotGrowRate() {
        SendPacer p = new SendPacer(0);
        for (int i = 1; i <= 50; i++) {
            p.onAcked(0, RTT, i * RTT);
        }
        assertThat(p.rateBitsPerSecond()).isEqualTo(5_000_000L);
    }

    @Test
    void worksAcrossNanoTimeOverflow() {
        long start = Long.MAX_VALUE - 5 * MS;
        SendPacer p = new SendPacer(start);
        for (int i = 0; i < 13; i++) {
            p.consumed(1200, start);
        }
        long wait = p.nanosUntilAllowed(1200, start);
        assertThat(wait).isPositive();
        assertThat(p.nanosUntilAllowed(1200, start + wait)).isZero();
        p.onLoss(start, start + 10 * MS); // wraps negative
        assertThat(p.rateBitsPerSecond()).isEqualTo(2_500_000L);
    }
}

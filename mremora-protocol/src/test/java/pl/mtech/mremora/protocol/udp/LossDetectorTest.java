package pl.mtech.mremora.protocol.udp;

import static java.time.Duration.ofMillis;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.util.List;
import org.junit.jupiter.api.Test;
import pl.mtech.mremora.protocol.udp.LossDetector.AckOutcome;
import pl.mtech.mremora.protocol.udp.RangeSet.Range;

class LossDetectorTest {

    private static final long MS = 1_000_000L;

    private final RttEstimator rtt = new RttEstimator();
    private final LossDetector d = new LossDetector(rtt);

    private void sendAll(int from, int to) {
        for (int i = from; i <= to; i++) {
            d.sent(i, 0, 100, true, false);
        }
    }

    @Test
    void packetThreshold() {
        sendAll(1, 5);
        AckOutcome o = d.onAck(List.of(new Range(2, 5)), 0, 10 * MS);
        assertThat(o.acked()).containsExactlyInAnyOrder(2L, 3L, 4L, 5L);
        assertThat(o.lost()).containsExactly(1L);
    }

    @Test
    void twoNewerNotEnough() {
        sendAll(1, 3);
        AckOutcome o = d.onAck(List.of(new Range(2, 3)), 0, 1 * MS);
        assertThat(o.lost()).isEmpty();
        assertThat(d.bytesInFlight()).isEqualTo(100);
        assertThat(d.lossTimeDeadline()).isPresent();
    }

    @Test
    void timeThreshold() {
        d.sent(1, 0, 100, true, false);
        d.sent(2, 0, 100, true, false);
        // rtt sample 40 ms from packet 2
        d.onAck(List.of(new Range(2, 2)), 0, 40 * MS);
        assertThat(rtt.smoothed()).isEqualTo(40 * MS);
        long deadline = d.lossTimeDeadline().getAsLong();
        assertThat(deadline).isEqualTo(45 * MS); // sent 0 + 9/8 * 40
        assertThat(d.detectByTime(deadline - 1)).isEmpty();
        assertThat(d.detectByTime(deadline)).containsExactly(1L);
        assertThat(d.bytesInFlight()).isZero();
        assertThat(d.lossTimeDeadline()).isEmpty();
    }

    @Test
    void ptoFiresWhenTailLost() {
        d.sent(1, 1000 * MS, 100, true, false);
        assertThat(d.ptoDeadline()).isEqualTo(1300 * MS);
        d.ptoFired();
        assertThat(d.ptoDeadline()).isEqualTo(1600 * MS);
        d.ptoFired();
        assertThat(d.ptoDeadline()).isEqualTo(2200 * MS);
        d.sent(2, 2000 * MS, 100, true, false);
        d.onAck(List.of(new Range(2, 2)), 0, 2010 * MS);
        d.sent(3, 3000 * MS, 100, true, false);
        assertThat(d.ptoDeadline()).isEqualTo(3000 * MS + 55 * MS); // doubling reset; sRTT 10, var 5
    }

    @Test
    void ptoCappedAt2s() {
        d.sent(1, 0, 100, true, false);
        for (int i = 0; i < 10; i++) {
            d.ptoFired();
        }
        assertThat(d.ptoDeadline()).isEqualTo(2000 * MS);
    }

    @Test
    void duplicateAckIgnored() {
        sendAll(1, 1);
        AckOutcome a = d.onAck(List.of(new Range(1, 1)), 0, 10 * MS);
        assertThat(a.acked()).containsExactly(1L);
        AckOutcome b = d.onAck(List.of(new Range(1, 1)), 0, 20 * MS);
        assertThat(b.acked()).isEmpty();
        assertThat(b.rttSampled()).isFalse();
        assertThat(b.ackedBytes()).isZero();
    }

    @Test
    void ackForUnknownNumberIgnored() {
        sendAll(1, 2);
        AckOutcome o = d.onAck(List.of(new Range(50, 60)), 0, 10 * MS);
        assertThat(o.acked()).isEmpty();
        assertThat(o.lost()).isEmpty();
        assertThat(d.bytesInFlight()).isEqualTo(200);
    }

    @Test
    void hugeRangeIsCheap() {
        sendAll(1, 3);
        AckOutcome o = assertTimeoutPreemptively(ofMillis(100),
                () -> d.onAck(List.of(new Range(0, Long.MAX_VALUE)), 0, 10 * MS));
        assertThat(o.acked()).containsExactlyInAnyOrder(1L, 2L, 3L);
        assertThat(d.bytesInFlight()).isZero();
    }

    @Test
    void rttFromLargestNewlyAcked() {
        d.sent(1, 0, 100, true, false);
        d.sent(2, 20 * MS, 100, true, false);
        AckOutcome o = d.onAck(List.of(new Range(1, 2)), 0, 60 * MS);
        assertThat(o.rttSampled()).isTrue();
        assertThat(rtt.latest()).isEqualTo(40 * MS);
    }

    @Test
    void pacerLimitedBytesReported() {
        d.sent(1, 0, 100, true, true);
        d.sent(2, 0, 200, true, false);
        d.sent(3, 0, 400, true, true);
        AckOutcome o = d.onAck(List.of(new Range(1, 3)), 0, 10 * MS);
        assertThat(o.ackedBytes()).isEqualTo(700);
        assertThat(o.ackedPacerLimitedBytes()).isEqualTo(500);
    }

    @Test
    void bytesInFlightTracksAckAndLoss() {
        sendAll(1, 5);
        assertThat(d.bytesInFlight()).isEqualTo(500);
        assertThat(d.anyAckElicitingInFlight()).isTrue();
        d.onAck(List.of(new Range(2, 5)), 0, 10 * MS); // 4 acked, 1 lost
        assertThat(d.bytesInFlight()).isZero();
        assertThat(d.anyAckElicitingInFlight()).isFalse();
    }

    @Test
    void ackResetsPtoDoublingWithoutRttChange() {
        d.sent(1, 0, 100, true, false);
        d.ptoFired();
        d.ptoFired();
        d.sent(2, 100 * MS, 100, false, false); // not ack-eliciting: cannot sample RTT
        d.onAck(List.of(new Range(2, 2)), 0, 110 * MS);
        d.sent(3, 1000 * MS, 100, true, false);
        assertThat(d.ptoDeadline()).isEqualTo(1300 * MS);
    }

    @Test
    void reusedPacketNumberLeavesStateUnchanged() {
        d.sent(1, 0, 100, false, false);
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> d.sent(1, 5, 900, true, false));
        assertThat(d.bytesInFlight()).isEqualTo(100);
        assertThat(d.anyAckElicitingInFlight()).isFalse();
    }

    @Test
    void overlappingRangesCountOnce() {
        sendAll(1, 3);
        AckOutcome o = d.onAck(List.of(new Range(1, 2), new Range(2, 3)), 0, 10 * MS);
        assertThat(o.acked()).containsExactlyInAnyOrder(1L, 2L, 3L);
        assertThat(o.ackedBytes()).isEqualTo(300);
        assertThat(d.bytesInFlight()).isZero();
    }
}

package pl.mtech.mremora.protocol.udp;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RttEstimatorTest {

    private static final long MS = 1_000_000L;

    @Test
    void firstSampleInitialises() {
        RttEstimator e = new RttEstimator();
        assertThat(e.hasSample()).isFalse();
        e.sample(40 * MS, 0);
        assertThat(e.hasSample()).isTrue();
        assertThat(e.smoothed()).isEqualTo(40 * MS);
        assertThat(e.variance()).isEqualTo(20 * MS);
        assertThat(e.latest()).isEqualTo(40 * MS);
        assertThat(e.min()).isEqualTo(40 * MS);
    }

    @Test
    void smoothing() {
        RttEstimator e = new RttEstimator();
        e.sample(40 * MS, 0);
        e.sample(80 * MS, 0);
        assertThat(e.smoothed()).isEqualTo(45 * MS);
        assertThat(e.variance()).isEqualTo(25 * MS);
        assertThat(e.latest()).isEqualTo(80 * MS);
        assertThat(e.min()).isEqualTo(40 * MS);
    }

    @Test
    void ackDelaySubtractedNotBelowMin() {
        RttEstimator e = new RttEstimator();
        e.sample(40 * MS, 0);
        e.sample(60 * MS, 10 * MS); // 50 >= min 40 -> adjusted to 50
        assertThat(e.smoothed()).isEqualTo(35 * MS + 50 * MS / 8);
        RttEstimator f = new RttEstimator();
        f.sample(40 * MS, 0);
        f.sample(60 * MS, 25 * MS); // 35 < min 40 -> not subtracted, 60
        assertThat(f.smoothed()).isEqualTo(35 * MS + 60 * MS / 8);
        assertThat(f.latest()).isEqualTo(60 * MS);
    }

    @Test
    void ptoBeforeSample300ms() {
        assertThat(new RttEstimator().pto()).isEqualTo(300 * MS);
    }

    @Test
    void ptoFormula() {
        RttEstimator e = new RttEstimator();
        e.sample(40 * MS, 0); // srtt 40, var 20 -> 40 + 80 + 25
        assertThat(e.pto()).isEqualTo(145 * MS);
        RttEstimator z = new RttEstimator();
        z.sample(0, 0); // var 0 -> floor 1 ms
        assertThat(z.pto()).isEqualTo(26 * MS);
    }
}

package pl.mtech.mremora.agent;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import pl.mtech.mremora.protocol.StreamConfig;

class StreamSizeTest {

    @Test
    void fullResolutionKeepsTheScreen() {
        assertThat(StreamSize.fit(2560, 1440, StreamConfig.FULL_RESOLUTION)).isEqualTo(new StreamSize(2560, 1440));
    }

    @Test
    void fitsByTheTighterSide() {
        assertThat(StreamSize.fit(2560, 1440, new StreamConfig(1600, 1600))).isEqualTo(new StreamSize(1600, 900));
    }

    @Test
    void heightLimited() {
        assertThat(StreamSize.fit(2560, 1440, new StreamConfig(1600, 450))).isEqualTo(new StreamSize(800, 450));
    }

    @Test
    void neverUpscales() {
        assertThat(StreamSize.fit(1280, 720, new StreamConfig(3840, 2160))).isEqualTo(new StreamSize(1280, 720));
    }

    @Test
    void nonIntegerRatioFloors() {
        assertThat(StreamSize.fit(1707, 960, new StreamConfig(1000, 1000))).isEqualTo(new StreamSize(1000, 562));
    }

    @Test
    void tinyLimitStillGivesOnePixel() {
        assertThat(StreamSize.fit(2560, 1440, new StreamConfig(1, 1))).isEqualTo(new StreamSize(1, 1));
    }
}

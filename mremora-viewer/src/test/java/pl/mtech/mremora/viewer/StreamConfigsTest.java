package pl.mtech.mremora.viewer;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import pl.mtech.mremora.protocol.ScreenInfo;
import pl.mtech.mremora.protocol.StreamConfig;

class StreamConfigsTest {

    private static final ScreenInfo SCREEN = new ScreenInfo(2560, 1440, 100);

    @Test
    void smallWindowGivesItsSize() {
        assertThat(StreamConfigs.forView(SCREEN, 1600, 900, 1.0)).isEqualTo(new StreamConfig(1600, 900));
    }

    @Test
    void letterboxUsesTheImageArea() {
        assertThat(StreamConfigs.forView(SCREEN, 1744, 915, 1.0)).isEqualTo(new StreamConfig(1627, 915));
    }

    @Test
    void hiDpiMultiplies() {
        assertThat(StreamConfigs.forView(SCREEN, 800, 450, 1.5)).isEqualTo(new StreamConfig(1200, 675));
    }

    @Test
    void biggerThanScreenIsFull() {
        assertThat(StreamConfigs.forView(SCREEN, 3000, 2000, 1.0)).isEqualTo(StreamConfig.FULL_RESOLUTION);
    }

    @Test
    void emptyViewGivesNull() {
        assertThat(StreamConfigs.forView(SCREEN, 0, 600, 1.0)).isNull();
    }
}

package pl.mtech.mremora.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.junit.jupiter.params.provider.EnumSource.Mode.EXCLUDE;

import java.awt.image.BufferedImage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class StreamBenchmarkTest {

    private static int[] pixels(BufferedImage img) {
        int w = img.getWidth();
        return img.getRGB(0, 0, w, img.getHeight(), null, 0, w);
    }

    @Test
    void staticScenarioRepeatsTheSameFrame() {
        var screen = new SyntheticScreen(Scenario.STATIC, 320, 180, 1);
        assertThat(pixels(screen.next())).isEqualTo(pixels(screen.next()));
    }

    @ParameterizedTest
    @EnumSource(value = Scenario.class, names = "STATIC", mode = EXCLUDE)
    void movingScenariosChangeBetweenFrames(Scenario s) {
        var screen = new SyntheticScreen(s, 320, 180, 1);
        assertThat(pixels(screen.next())).isNotEqualTo(pixels(screen.next()));
    }

    @ParameterizedTest
    @EnumSource(Scenario.class)
    void sameSeedGivesSameFrames(Scenario s) {
        var a = new SyntheticScreen(s, 320, 180, 7);
        var b = new SyntheticScreen(s, 320, 180, 7);
        for (int i = 0; i < 3; i++) {
            assertThat(pixels(a.next())).isEqualTo(pixels(b.next()));
        }
    }

    @Test
    void framesAreIntRgb() {
        assertThat(new SyntheticScreen(Scenario.VIDEO, 320, 180, 1).next().getType())
                .isEqualTo(BufferedImage.TYPE_INT_RGB);
    }

    @Test
    void benchmarkSumsBytesFromTheEncoder() {
        BenchResult r = StreamBenchmark.run(Scenario.STATIC, 320, 180, 10, frame -> 100);
        assertThat(r.frames()).isEqualTo(10);
        assertThat(r.totalBytes()).isEqualTo(1000);
        assertThat(r.megabitsPerSecondAt(30)).isCloseTo(0.024, within(1e-9));
    }
}

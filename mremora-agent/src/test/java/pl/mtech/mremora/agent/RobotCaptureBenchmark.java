package pl.mtech.mremora.agent;

import java.awt.AWTException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Measures raw {@code Robot} capture time on the real screen. Opt in with
 * {@code -Dmremora.desktop=true -Dmremora.bench=true}. On GNOME/Wayland it may block on the portal dialog.
 */
@EnabledIfSystemProperty(named = "mremora.desktop", matches = "true")
@EnabledIfSystemProperty(named = "mremora.bench", matches = "true")
class RobotCaptureBenchmark {

    @Test
    void captureTime() throws AWTException {
        var capturer = RobotScreenCapturer.forPrimaryScreen();
        for (int i = 0; i < 5; i++) {
            capturer.capture();
        }
        long total = 0;
        long max = 0;
        int n = 50;
        for (int i = 0; i < n; i++) {
            long start = System.nanoTime();
            capturer.capture();
            long elapsed = System.nanoTime() - start;
            total += elapsed;
            max = Math.max(max, elapsed);
        }
        var info = capturer.info();
        System.out.printf("Robot capture %dx%d: avg %.1f ms, max %.1f ms (%d frames)%n",
                info.width(), info.height(), total / 1e6 / n, max / 1e6, n);
    }
}

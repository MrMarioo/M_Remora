package pl.mtech.mremora.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.awt.AWTException;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.MouseInfo;
import java.awt.Point;
import java.awt.Robot;
import java.awt.image.BufferedImage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import pl.mtech.mremora.protocol.ScreenInfo;

/**
 * Runs against the real desktop (moves the cursor!). Opt in with {@code -Dmremora.desktop=true}.
 *
 * <p>On GNOME/Wayland the JDK Robot goes through xdg-desktop-portal and blocks until the
 * permission dialog is accepted, so this test can hang until someone clicks it.
 */
@EnabledIfSystemProperty(named = "mremora.desktop", matches = "true")
class RobotDesktopTest {

    private Robot robot;
    private ScreenGeometry geometry;
    private RobotInputInjector injector;

    @BeforeEach
    void setUp() throws AWTException {
        assumeFalse(GraphicsEnvironment.isHeadless());
        GraphicsDevice device = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice();
        robot = new Robot(device);
        geometry = ScreenGeometry.of(device.getDefaultConfiguration());
        injector = new RobotInputInjector(robot, geometry);
    }

    @Test
    void captureMatchesReportedPhysicalSize() throws AWTException {
        var capturer = RobotScreenCapturer.forPrimaryScreen();
        ScreenInfo info = capturer.info();

        BufferedImage image = capturer.capture();

        assertThat(image.getWidth()).isEqualTo(info.width());
        assertThat(image.getHeight()).isEqualTo(info.height());
    }

    @Test
    void moveLandsOnLogicalPoint() {
        ScreenInfo info = geometry.info();
        int x = info.width() / 2;
        int y = info.height() / 2;

        injector.move(x, y);
        robot.waitForIdle();

        assertThat(MouseInfo.getPointerInfo().getLocation()).isEqualTo(geometry.toLogical(x, y));
    }

    @Test
    void moveOutsideScreenStaysOnEdge() {
        injector.move(-100, -100);
        robot.waitForIdle();

        var bounds = geometry.logicalBounds();
        assertThat(MouseInfo.getPointerInfo().getLocation()).isEqualTo(new Point(bounds.x, bounds.y));
    }
}

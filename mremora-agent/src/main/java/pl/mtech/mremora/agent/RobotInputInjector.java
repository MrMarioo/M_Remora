package pl.mtech.mremora.agent;

import java.awt.AWTException;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Point;
import java.awt.Robot;
import java.awt.event.InputEvent;
import java.util.Objects;
import pl.mtech.mremora.protocol.MouseButton;

/**
 * {@link InputInjector} backed by {@link Robot}; converts physical coordinates to logical ones.
 *
 * <p>Do not share a {@link Robot} with the capture loop: Robot methods are synchronized, so input
 * would wait behind full-screen captures. Use {@link #forPrimaryScreen()} to get a dedicated one.
 */
final class RobotInputInjector implements InputInjector {

    private final Robot robot;
    private final ScreenGeometry geometry;

    RobotInputInjector(Robot robot, ScreenGeometry geometry) {
        this.robot = Objects.requireNonNull(robot, "robot");
        this.geometry = Objects.requireNonNull(geometry, "geometry");
    }

    static RobotInputInjector forPrimaryScreen() throws AWTException {
        GraphicsDevice device = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice();
        return new RobotInputInjector(new Robot(device), ScreenGeometry.of(device.getDefaultConfiguration()));
    }

    @Override
    public void move(int x, int y) {
        Point p = geometry.toLogical(x, y);
        robot.mouseMove(p.x, p.y);
    }

    @Override
    public void press(MouseButton.Button b) {
        robot.mousePress(buttonMask(b));
    }

    @Override
    public void release(MouseButton.Button b) {
        robot.mouseRelease(buttonMask(b));
    }

    @Override
    public void wheel(int notches) {
        robot.mouseWheel(notches);
    }

    static int buttonMask(MouseButton.Button b) {
        return switch (b) {
            case LEFT -> InputEvent.BUTTON1_DOWN_MASK;
            case MIDDLE -> InputEvent.BUTTON2_DOWN_MASK;
            case RIGHT -> InputEvent.BUTTON3_DOWN_MASK;
        };
    }
}

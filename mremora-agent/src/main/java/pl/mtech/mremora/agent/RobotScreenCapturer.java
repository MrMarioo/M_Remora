package pl.mtech.mremora.agent;

import java.awt.AWTException;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Image;
import java.awt.Robot;
import java.awt.image.BufferedImage;
import java.util.Objects;
import pl.mtech.mremora.protocol.ScreenInfo;

/**
 * {@link ScreenCapturer} backed by {@link Robot}; returns images in physical resolution even on HiDPI screens.
 *
 * <p>Do not share a {@link Robot} with the input path: Robot methods are synchronized, so input
 * would wait behind full-screen captures. Use {@link #forPrimaryScreen()} to get a dedicated one.
 */
final class RobotScreenCapturer implements ScreenCapturer {

    private final Robot robot;
    private final ScreenGeometry geometry;

    RobotScreenCapturer(Robot robot, ScreenGeometry geometry) {
        this.robot = Objects.requireNonNull(robot, "robot");
        this.geometry = Objects.requireNonNull(geometry, "geometry");
    }

    static RobotScreenCapturer forPrimaryScreen() throws AWTException {
        GraphicsDevice device = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice();
        return new RobotScreenCapturer(new Robot(device), ScreenGeometry.of(device.getDefaultConfiguration()));
    }

    @Override
    public ScreenInfo info() {
        return geometry.info();
    }

    @Override
    public BufferedImage capture() {
        ScreenInfo info = info();
        Image variant = robot.createMultiResolutionScreenCapture(geometry.logicalBounds())
                .getResolutionVariant(info.width(), info.height());
        if (variant instanceof BufferedImage image
                && image.getWidth() == info.width() && image.getHeight() == info.height()) {
            return image;
        }
        var image = new BufferedImage(info.width(), info.height(), BufferedImage.TYPE_INT_RGB);
        var g = image.createGraphics();
        try {
            g.drawImage(variant, 0, 0, info.width(), info.height(), null);
        } finally {
            g.dispose();
        }
        return image;
    }
}

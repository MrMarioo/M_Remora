package pl.mtech.mremora.agent;

import java.awt.GraphicsConfiguration;
import java.awt.Point;
import java.awt.Rectangle;
import pl.mtech.mremora.protocol.ScreenInfo;

/**
 * Bridges the two coordinate systems of the agent: AWT works in logical (user space) coordinates,
 * the wire protocol in physical pixels. This is the only class that knows about both.
 *
 * @param logicalBounds screen bounds in logical coordinates (origin may be non-zero on multi-monitor setups)
 * @param scale         OS scaling factor, physical pixels per logical pixel (1.25 = 125%)
 */
record ScreenGeometry(Rectangle logicalBounds, double scale) {

    ScreenGeometry {
        if (!(scale > 0) || Double.isInfinite(scale)) {
            throw new IllegalArgumentException("scale must be positive and finite, was " + scale);
        }
        if (logicalBounds.isEmpty()) {
            throw new IllegalArgumentException("Screen bounds must not be empty, was " + logicalBounds);
        }
        logicalBounds = new Rectangle(logicalBounds); // Rectangle is mutable
    }

    static ScreenGeometry of(GraphicsConfiguration gc) {
        return new ScreenGeometry(gc.getBounds(), gc.getDefaultTransform().getScaleX());
    }

    /** Screen description to announce to the viewer, in physical pixels. */
    ScreenInfo info() {
        return new ScreenInfo(physicalWidth(), physicalHeight(), (int) Math.round(scale * 100));
    }

    /**
     * Converts a physical pixel position (relative to the screen's top-left corner) to logical
     * coordinates usable by {@link java.awt.Robot}. Out-of-range input is clamped to the screen.
     */
    Point toLogical(int physicalX, int physicalY) {
        int x = Math.clamp(physicalX, 0, physicalWidth() - 1);
        int y = Math.clamp(physicalY, 0, physicalHeight() - 1);
        return new Point(
                logicalBounds.x + (int) Math.floor(x / scale),
                logicalBounds.y + (int) Math.floor(y / scale));
    }

    private int physicalWidth() {
        return (int) Math.round(logicalBounds.width * scale);
    }

    private int physicalHeight() {
        return (int) Math.round(logicalBounds.height * scale);
    }
}

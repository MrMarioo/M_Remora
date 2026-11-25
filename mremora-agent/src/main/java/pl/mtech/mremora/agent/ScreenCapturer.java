package pl.mtech.mremora.agent;

import java.awt.image.BufferedImage;
import pl.mtech.mremora.protocol.ScreenInfo;

/** Source of screen images, in the agent's physical pixel resolution. */
public interface ScreenCapturer {

    /** Description of the captured screen, in physical pixels. */
    ScreenInfo info();

    /**
     * Captures the current screen content.
     *
     * @return image of {@code info().width() x info().height()} physical pixels
     */
    BufferedImage capture();
}

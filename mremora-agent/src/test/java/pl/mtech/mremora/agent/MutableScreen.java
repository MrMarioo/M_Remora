package pl.mtech.mremora.agent;

import java.awt.image.BufferedImage;
import pl.mtech.mremora.protocol.ScreenInfo;

/** Capturer whose image can be replaced between captures. */
final class MutableScreen implements ScreenCapturer {

    private volatile BufferedImage image;

    MutableScreen(BufferedImage image) {
        this.image = image;
    }

    void set(BufferedImage image) {
        this.image = image;
    }

    BufferedImage image() {
        return image;
    }

    @Override
    public ScreenInfo info() {
        return new ScreenInfo(image.getWidth(), image.getHeight(), 100);
    }

    @Override
    public BufferedImage capture() {
        return image;
    }
}

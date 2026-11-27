package pl.mtech.mremora.viewer;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import pl.mtech.mremora.agent.InputInjector;
import pl.mtech.mremora.agent.ScreenCapturer;
import pl.mtech.mremora.protocol.MouseButton;
import pl.mtech.mremora.protocol.ScreenInfo;

/**
 * Screen and mouse of the agent in viewer tests: a black image (replaceable with {@link #setImage}), mouse calls
 * recorded as strings ({@code move 7,8}, {@code press LEFT}). Thread-safe.
 */
final class FakeDesktop implements ScreenCapturer, InputInjector {

    private volatile BufferedImage image;
    private final List<String> calls = new ArrayList<>();

    FakeDesktop(int width, int height) {
        this.image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
    }

    /** What the next captures return; keep the size, the agent's {@link ScreenInfo} does not change. */
    void setImage(BufferedImage image) {
        this.image = image;
    }

    synchronized List<String> calls() {
        return List.copyOf(calls);
    }

    @Override
    public ScreenInfo info() {
        return new ScreenInfo(image.getWidth(), image.getHeight(), 100);
    }

    @Override
    public BufferedImage capture() {
        return image;
    }

    @Override
    public synchronized void move(int x, int y) {
        calls.add("move " + x + "," + y);
    }

    @Override
    public synchronized void press(MouseButton.Button b) {
        calls.add("press " + b);
    }

    @Override
    public synchronized void release(MouseButton.Button b) {
        calls.add("release " + b);
    }

    @Override
    public synchronized void wheel(int notches) {
        calls.add("wheel " + notches);
    }
}

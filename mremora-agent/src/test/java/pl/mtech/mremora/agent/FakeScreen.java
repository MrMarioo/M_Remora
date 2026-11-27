package pl.mtech.mremora.agent;

import java.awt.image.BufferedImage;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import pl.mtech.mremora.protocol.MouseButton;
import pl.mtech.mremora.protocol.ScreenInfo;

/**
 * Screen and mouse for server tests: returns a fixed image (replaceable with {@link #setImage}), can start failing from the N-th capture,
 * counts captures and records mouse calls like {@code InputDispatcherTest} ({@code move 10,20}, {@code press LEFT}).
 * Moves can be held at a gate to block the input thread. Thread-safe.
 */
final class FakeScreen implements ScreenCapturer, InputInjector {

    private volatile BufferedImage image;
    private final AtomicInteger captures = new AtomicInteger();
    private final List<String> calls = new ArrayList<>();
    private volatile int failFromCapture = Integer.MAX_VALUE;
    private volatile CountDownLatch moveGate;
    private final CountDownLatch moveBlocked = new CountDownLatch(1);

    FakeScreen(BufferedImage image) {
        this.image = image;
    }

    /** A black screen of the given size. */
    FakeScreen(int width, int height) {
        this(new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB));
    }

    /** Makes every later capture return {@code image}. */
    void setImage(BufferedImage image) {
        this.image = image;
    }

    /** Makes the {@code n}-th capture (1-based) and every later one throw. */
    FakeScreen failFromCapture(int n) {
        failFromCapture = n;
        return this;
    }

    /** Makes every later {@link #move} wait until {@link #openMoveGate()}; recorded only once let through. */
    void closeMoveGate() {
        moveGate = new CountDownLatch(1);
    }

    void openMoveGate() {
        CountDownLatch gate = moveGate;
        if (gate != null) {
            gate.countDown();
        }
    }

    /** Waits until a {@link #move} is held at the closed gate. */
    boolean awaitMoveBlocked(Duration timeout) throws InterruptedException {
        return moveBlocked.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    int captures() {
        return captures.get();
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
        int n = captures.incrementAndGet();
        if (n >= failFromCapture) {
            throw new IllegalStateException("capture " + n + " failed on purpose");
        }
        return image;
    }

    @Override
    public void move(int x, int y) {
        CountDownLatch gate = moveGate;
        if (gate != null) {
            moveBlocked.countDown();
            try {
                gate.await(); // outside the monitor, so calls() stays readable meanwhile
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        synchronized (this) {
            calls.add("move " + x + "," + y);
        }
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

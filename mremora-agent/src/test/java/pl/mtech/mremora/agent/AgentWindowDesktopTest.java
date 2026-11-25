package pl.mtech.mremora.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.awt.Frame;
import java.awt.GraphicsEnvironment;
import java.awt.Window;
import java.awt.event.WindowEvent;
import java.lang.reflect.InvocationTargetException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/** Shows a real Swing window briefly. Opt in with {@code -Dmremora.desktop=true}. */
@EnabledIfSystemProperty(named = "mremora.desktop", matches = "true")
class AgentWindowDesktopTest {

    private final AtomicInteger quit = new AtomicInteger();
    private AgentWindow window;

    @BeforeEach
    void setUp() {
        assumeFalse(GraphicsEnvironment.isHeadless());
        window = AgentWindow.open(quit::incrementAndGet);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (window != null) {
            window.close();
            flushEdt();
        }
    }

    private static void flushEdt() throws InvocationTargetException, InterruptedException {
        SwingUtilities.invokeAndWait(() -> {});
    }

    private Window frame() {
        return SwingUtilities.getWindowAncestor(window.endButton());
    }

    private void click(int times) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            for (int i = 0; i < times; i++) {
                window.endButton().doClick();
            }
        });
    }

    private void closing() throws Exception {
        SwingUtilities.invokeAndWait(() -> frame().dispatchEvent(new WindowEvent(frame(), WindowEvent.WINDOW_CLOSING)));
    }

    @Test
    void endButtonCallsCallbackOnce() throws Exception {
        AtomicInteger ended = new AtomicInteger();
        window.showSession("viewer-1", ended::incrementAndGet);
        flushEdt();

        click(2);
        flushEdt();

        assertThat(ended).hasValue(1);
        assertThat(frame().isVisible()).isTrue();
        assertThat(window.endButton().isEnabled()).isFalse();
    }

    @Test
    void closingDuringSessionEndsSessionNotApp() throws Exception {
        AtomicInteger ended = new AtomicInteger();
        window.showSession("viewer-1", ended::incrementAndGet);
        flushEdt();

        closing();
        flushEdt();

        assertThat(ended).hasValue(1);
        assertThat(quit).hasValue(0);
    }

    @Test
    void closingWhileWaitingQuits() throws Exception {
        window.showCode("123456");
        flushEdt();

        closing();
        flushEdt();

        assertThat(quit).hasValue(1);
    }

    @Test
    void newSessionRearmsEndButton() throws Exception {
        AtomicInteger first = new AtomicInteger();
        AtomicInteger second = new AtomicInteger();
        window.showSession("viewer-1", first::incrementAndGet);
        flushEdt();
        click(1);
        window.showCode("123456");
        window.showSession("viewer-2", second::incrementAndGet);
        flushEdt();

        assertThat(window.endButton().isEnabled()).isTrue();
        click(1);
        flushEdt();

        assertThat(first).hasValue(1);
        assertThat(second).hasValue(1);
    }

    @Test
    void showsTextsPerState() throws Exception {
        window.showCode("123456");
        flushEdt();
        assertThat(window.statusText()).isEqualTo("Pairing code: 123 456\nWaiting for a viewer…");
        assertThat(window.endButton().isVisible()).isFalse();

        window.showLocked(Duration.ofMillis(29_500));
        flushEdt();
        assertThat(window.statusText()).isEqualTo("Too many wrong codes – wait 30 s");

        window.showSession("viewer-1", () -> {});
        flushEdt();
        assertThat(window.statusText()).isEqualTo("Remote session active: viewer-1");
        assertThat(window.endButton().isVisible()).isTrue();
        assertThat(window.endButton().getText()).isEqualTo("End session");
    }

    @Test
    void windowIsAlwaysOnTopInTopRightCorner() {
        Window w = frame();
        var screen = GraphicsEnvironment.getLocalGraphicsEnvironment()
                .getDefaultScreenDevice().getDefaultConfiguration().getBounds();
        assertThat(w.isAlwaysOnTop()).isTrue();
        assertThat(w.getY()).isEqualTo(screen.y);
        assertThat(w.getX() + w.getWidth()).isEqualTo(screen.x + screen.width);
    }

    @Test
    void sessionRestoresMinimizedWindow() throws Exception {
        window.showCode("123456");
        flushEdt();
        Frame f = (Frame) frame();
        SwingUtilities.invokeAndWait(() -> f.setExtendedState(Frame.ICONIFIED));
        assumeTrue(await(() -> iconified(f)), "display did not iconify the window; cannot verify restore");

        window.showSession("viewer-1", () -> {});

        assertThat(await(() -> !iconified(f))).as("window restored").isTrue();
        assertThat(f.getExtendedState() & Frame.ICONIFIED).isZero();
        assertThat(f.isVisible()).isTrue();
    }

    private static boolean iconified(Frame f) {
        return (f.getExtendedState() & Frame.ICONIFIED) != 0;
    }

    private static boolean await(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(50);
        }
        return condition.getAsBoolean();
    }
}

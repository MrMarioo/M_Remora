package pl.mtech.mremora.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.awt.GraphicsEnvironment;
import java.awt.Window;
import java.awt.event.WindowEvent;
import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/** Shows a real Swing window briefly. Opt in with {@code -Dmremora.desktop=true}. */
@EnabledIfSystemProperty(named = "mremora.desktop", matches = "true")
class SessionIndicatorDesktopTest {

    private final AtomicInteger ended = new AtomicInteger();
    private SessionIndicator indicator;

    @BeforeEach
    void setUp() {
        assumeFalse(GraphicsEnvironment.isHeadless());
        indicator = SessionIndicator.open("viewer-1", ended::incrementAndGet);
    }

    @AfterEach
    void tearDown() {
        if (indicator != null) {
            indicator.close();
        }
    }

    private static void flushEdt() throws InvocationTargetException, InterruptedException {
        SwingUtilities.invokeAndWait(() -> {});
    }

    @Test
    void endButtonInvokesCallbackOnce() throws Exception {
        var button = indicator.endButton();
        Window window = SwingUtilities.getWindowAncestor(button);

        SwingUtilities.invokeAndWait(() -> {
            button.doClick();
            button.doClick();
        });
        flushEdt();

        assertThat(ended).hasValue(1);
        assertThat(window.isVisible()).isFalse();
    }

    @Test
    void closingWindowCountsAsEnd() throws Exception {
        Window window = SwingUtilities.getWindowAncestor(indicator.endButton());

        SwingUtilities.invokeAndWait(() -> window.dispatchEvent(new WindowEvent(window, WindowEvent.WINDOW_CLOSING)));
        flushEdt();

        assertThat(ended).hasValue(1);
        assertThat(window.isVisible()).isFalse();
    }

    @Test
    void closeDoesNotInvokeCallback() throws Exception {
        Window window = SwingUtilities.getWindowAncestor(indicator.endButton());

        indicator.close();
        flushEdt();

        assertThat(ended).hasValue(0);
        assertThat(window.isVisible()).isFalse();
    }
}

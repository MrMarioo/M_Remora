package pl.mtech.mremora.agent;

import java.awt.FlowLayout;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.lang.System.Logger;
import java.lang.reflect.InvocationTargetException;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;

/**
 * Always-on-top "remote session active" window with an "End session" button, so the local user can
 * stop a session at any time.
 */
public final class SessionIndicator {

    private static final Logger LOG = System.getLogger(SessionIndicator.class.getName());

    private final JFrame frame = new JFrame("MRemora – remote session");
    private final JButton endButton = new JButton("End session");
    private final Runnable onEndRequested;
    private final AtomicBoolean finished = new AtomicBoolean();

    private SessionIndicator(String viewerLabel, Runnable onEndRequested) {
        this.onEndRequested = onEndRequested;
        frame.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        frame.setAlwaysOnTop(true);
        frame.setLayout(new FlowLayout(FlowLayout.CENTER, 12, 8));
        frame.add(new JLabel("Remote session active: " + viewerLabel));
        frame.add(endButton);
        frame.pack();
        Rectangle screen =
                GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice().getDefaultConfiguration().getBounds();
        frame.setLocation(screen.x + screen.width - frame.getWidth(), screen.y);

        endButton.addActionListener(e -> endRequested());
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                endRequested();
            }
        });
    }

    /**
     * Builds and shows the window on the EDT and returns once it is shown. Must not be called from
     * the EDT.
     *
     * @param onEndRequested called at most once, on the EDT, when the user ends the session. It must
     *     be a fast, non-throwing signal (e.g. set a flag or close the connection) and must not call
     *     {@link InputDispatcher} directly, which is single-threaded on the session thread. A
     *     {@link RuntimeException} it throws is logged at ERROR, since the window is already gone.
     */
    public static SessionIndicator open(String viewerLabel, Runnable onEndRequested) {
        Objects.requireNonNull(viewerLabel, "viewerLabel");
        Objects.requireNonNull(onEndRequested, "onEndRequested");
        SessionIndicator[] holder = new SessionIndicator[1];
        try {
            SwingUtilities.invokeAndWait(() -> {
                holder[0] = new SessionIndicator(viewerLabel, onEndRequested);
                holder[0].frame.setVisible(true);
            });
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while opening session indicator", e);
        } catch (InvocationTargetException e) {
            throw new IllegalStateException("cannot open session indicator", e.getCause());
        }
        return holder[0];
    }

    /**
     * Closes the window without invoking the callback (the session ended for another reason).
     * Asynchronous: the window disappears later on the EDT, but callback suppression is immediate.
     */
    public void close() {
        finished.set(true);
        SwingUtilities.invokeLater(frame::dispose);
    }

    JButton endButton() {
        return endButton;
    }

    private void endRequested() {
        if (finished.compareAndSet(false, true)) {
            frame.dispose();
            try {
                onEndRequested.run();
            } catch (RuntimeException e) {
                LOG.log(Logger.Level.ERROR, "onEndRequested callback failed", e);
            }
        }
    }
}

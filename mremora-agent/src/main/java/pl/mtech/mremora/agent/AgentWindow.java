package pl.mtech.mremora.agent;

import java.awt.Component;
import java.awt.Font;
import java.awt.Frame;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.lang.System.Logger;
import java.lang.reflect.InvocationTargetException;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;

/**
 * Always-on-top agent window in the top-right screen corner: pairing code, lock countdown, or an
 * active session with an "End session" button. Swing state is touched on the EDT only; the
 * {@link AgentUi} methods may be called from any thread.
 */
public final class AgentWindow implements AgentUi {

    private static final Logger LOG = System.getLogger(AgentWindow.class.getName());

    /** One {@code showSession} call; its callback fires at most once. */
    private record Session(Runnable onEnd, AtomicBoolean fired) {}

    private final JFrame frame = new JFrame("MRemora");
    private final JLabel title = new JLabel();
    private final JLabel subtitle = new JLabel();
    private final JLabel address = new JLabel();
    private final JLabel moreAddresses = new JLabel();
    private final JButton endButton = new JButton("End session");
    private final Runnable onQuit;

    /** EDT-only. */
    private Session session;

    private AgentWindow(Runnable onQuit) {
        this.onQuit = onQuit;
        frame.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        frame.setAlwaysOnTop(true);

        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(BorderFactory.createEmptyBorder(10, 16, 10, 16));
        title.setAlignmentX(Component.CENTER_ALIGNMENT);
        subtitle.setAlignmentX(Component.CENTER_ALIGNMENT);
        address.setAlignmentX(Component.CENTER_ALIGNMENT);
        moreAddresses.setAlignmentX(Component.CENTER_ALIGNMENT);
        moreAddresses.setFont(moreAddresses.getFont().deriveFont(Font.PLAIN, moreAddresses.getFont().getSize2D() - 2));
        address.setVisible(false);
        moreAddresses.setVisible(false);
        endButton.setAlignmentX(Component.CENTER_ALIGNMENT);
        panel.add(title);
        panel.add(subtitle);
        panel.add(address);
        panel.add(moreAddresses);
        panel.add(Box.createVerticalStrut(8));
        panel.add(endButton);
        frame.add(panel);

        frame.addWindowStateListener(e -> {
            if (session != null && (e.getNewState() & Frame.ICONIFIED) != 0) {
                frame.setExtendedState(Frame.NORMAL);
            }
        });
        endButton.addActionListener(e -> endSession());
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                if (session != null) {
                    endSession();
                } else {
                    runSafely(AgentWindow.this.onQuit, "onQuit callback failed");
                }
            }
        });
    }

    /**
     * Builds and shows the window on the EDT and returns once it is shown. Must not be called from
     * the EDT.
     *
     * @param onQuit called on the EDT when the user closes the window outside a session; must be a
     *     fast, non-throwing signal. A {@link RuntimeException} it throws is logged at ERROR.
     */
    public static AgentWindow open(Runnable onQuit) {
        Objects.requireNonNull(onQuit, "onQuit");
        AgentWindow[] holder = new AgentWindow[1];
        try {
            SwingUtilities.invokeAndWait(() -> {
                holder[0] = new AgentWindow(onQuit);
                holder[0].render("Waiting for a viewer…", "", false);
                holder[0].frame.setVisible(true);
            });
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while opening agent window", e);
        } catch (InvocationTargetException e) {
            throw new IllegalStateException("cannot open agent window", e.getCause());
        }
        return holder[0];
    }

    @Override
    public void showCode(String code) {
        Objects.requireNonNull(code, "code");
        String shown = code.length() > 3 ? code.substring(0, 3) + " " + code.substring(3) : code;
        SwingUtilities.invokeLater(() -> {
            session = null;
            render("Pairing code: " + shown, "Waiting for a viewer…", false);
        });
    }

    @Override
    public void showLocked(Duration remaining) {
        Objects.requireNonNull(remaining, "remaining");
        long seconds = Math.max(0, remaining.plusMillis(999).toSeconds());
        SwingUtilities.invokeLater(() -> {
            session = null;
            render("Too many wrong codes – wait " + seconds + " s", "", false);
        });
    }

    @Override
    public void showSession(String viewerLabel, Runnable onEndRequested) {
        Objects.requireNonNull(viewerLabel, "viewerLabel");
        Objects.requireNonNull(onEndRequested, "onEndRequested");
        Session next = new Session(onEndRequested, new AtomicBoolean());
        SwingUtilities.invokeLater(() -> {
            session = next;
            endButton.setEnabled(true);
            render("Remote session active: " + viewerLabel, "", true);
            frame.setExtendedState(Frame.NORMAL);
            frame.setVisible(true);
            frame.toFront();
        });
    }

    /**
     * Shows where a viewer can connect, below the status text in every state. Any thread.
     *
     * @param addresses best first; the first is shown prominently, the rest on one smaller line
     * @param port the port the agent actually listens on
     */
    public void showAddresses(List<String> addresses, int port) {
        List<String> copy = List.copyOf(addresses);
        SwingUtilities.invokeLater(() -> {
            if (copy.isEmpty()) {
                address.setText("No network connection");
                moreAddresses.setText("");
            } else {
                address.setText("Connect to: " + copy.get(0) + " (port " + port + ")");
                moreAddresses.setText(copy.size() > 1 ? "also: " + String.join(", ", copy.subList(1, copy.size())) : "");
            }
            address.setVisible(true);
            moreAddresses.setVisible(!moreAddresses.getText().isEmpty());
            relayout();
        });
    }

    /** Closes the window without invoking any callback. */
    public void close() {
        SwingUtilities.invokeLater(() -> {
            session = null;
            frame.dispose();
        });
    }

    JButton endButton() {
        return endButton;
    }

    String statusText() {
        return title.getText()
                + (subtitle.getText().isEmpty() ? "" : "\n" + subtitle.getText())
                + (address.isVisible() ? "\n" + address.getText() : "")
                + (moreAddresses.isVisible() ? "\n" + moreAddresses.getText() : "");
    }

    private void endSession() {
        Session current = session;
        if (current != null && current.fired().compareAndSet(false, true)) {
            endButton.setEnabled(false);
            runSafely(current.onEnd(), "onEndRequested callback failed");
        }
    }

    private static void runSafely(Runnable callback, String message) {
        try {
            callback.run();
        } catch (RuntimeException e) {
            LOG.log(Logger.Level.ERROR, message, e);
        }
    }

    private void render(String titleText, String subtitleText, boolean sessionActive) {
        title.setText(titleText);
        subtitle.setText(subtitleText);
        subtitle.setVisible(!subtitleText.isEmpty());
        endButton.setVisible(sessionActive);
        relayout();
    }

    private void relayout() {
        frame.pack();
        Rectangle screen = GraphicsEnvironment.getLocalGraphicsEnvironment()
                .getDefaultScreenDevice()
                .getDefaultConfiguration()
                .getBounds();
        frame.setLocation(screen.x + screen.width - frame.getWidth(), screen.y);
    }
}

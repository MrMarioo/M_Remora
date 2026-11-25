package pl.mtech.mremora.viewer;

import java.awt.GraphicsEnvironment;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.lang.reflect.InvocationTargetException;
import javafx.application.Application;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import pl.mtech.mremora.logging.LogFiles;

/**
 * Entry point of the viewer.
 *
 * <p>A separate class, because a {@code main} inside an {@link Application} subclass started from the classpath fails
 * with "JavaFX runtime components are missing".
 */
public final class ViewerMain {

    private static final Logger LOG = System.getLogger(ViewerMain.class.getName());

    private ViewerMain() {}

    public static void main(String[] args) {
        LogFiles.install("viewer");
        try {
            Application.launch(ViewerApp.class, args);
        } catch (RuntimeException e) { // e.g. no JavaFX toolkit: the packaged launcher has no console
            LOG.log(Level.ERROR, "Unexpected start failure", e);
            String reason = e.getMessage() != null ? e.getMessage() : e.getClass().getName();
            String message = "MRemora Viewer could not start: " + reason;
            System.err.println(message);
            showError(message);
            System.exit(1);
        }
    }

    /** JavaFX is unusable here, so the dialog is Swing; skipped headless or with {@code MREMORA_NO_DIALOGS=1}. */
    private static void showError(String message) {
        if (GraphicsEnvironment.isHeadless() || "1".equals(System.getenv("MREMORA_NO_DIALOGS"))) {
            return;
        }
        try {
            SwingUtilities.invokeAndWait(() ->
                    JOptionPane.showMessageDialog(null, message, "MRemora Viewer", JOptionPane.ERROR_MESSAGE));
        } catch (InvocationTargetException e) {
            LOG.log(Level.ERROR, "Cannot show the error dialog", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.log(Level.ERROR, "Interrupted while showing the error dialog", e);
        }
    }
}

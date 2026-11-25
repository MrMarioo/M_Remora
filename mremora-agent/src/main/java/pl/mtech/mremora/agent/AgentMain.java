package pl.mtech.mremora.agent;

import java.awt.AWTException;
import java.awt.GraphicsEnvironment;
import java.lang.reflect.InvocationTargetException;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import pl.mtech.mremora.logging.LogFiles;

/** Runnable entry point of the agent: {@code AgentMain [--port N]}. */
public final class AgentMain {

    static final int DEFAULT_PORT = 7420;
    private static final String USAGE = "Usage: AgentMain [--port N]  (N in 1..65535, default " + DEFAULT_PORT + ")";

    private static final System.Logger LOG = System.getLogger(AgentMain.class.getName());

    private AgentMain() {}

    /** Logs a fatal start error, tells the user (no console in the packaged launcher), and exits. */
    private static void fail(String message, int exitCode) {
        LOG.log(System.Logger.Level.ERROR, message);
        System.err.println(message);
        if (!GraphicsEnvironment.isHeadless() && !"1".equals(System.getenv("MREMORA_NO_DIALOGS"))) {
            try {
                SwingUtilities.invokeAndWait(() ->
                        JOptionPane.showMessageDialog(null, message, "MRemora Agent", JOptionPane.ERROR_MESSAGE));
            } catch (InvocationTargetException e) {
                LOG.log(System.Logger.Level.ERROR, "Cannot show the error dialog", e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                LOG.log(System.Logger.Level.ERROR, "Interrupted while showing the error dialog", e);
            }
        }
        System.exit(exitCode);
    }

    /**
     * Parses the command line.
     *
     * @return the port to listen on
     * @throws IllegalArgumentException with the usage text for anything but no arguments or {@code --port N}
     */
    static int parsePort(String[] args) {
        if (args.length == 0) {
            return DEFAULT_PORT;
        }
        if (args.length != 2 || !"--port".equals(args[0])) {
            throw new IllegalArgumentException(USAGE);
        }
        try {
            int port = Integer.parseInt(args[1]);
            if (port < 1 || port > 65535) {
                throw new IllegalArgumentException(USAGE);
            }
            return port;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(USAGE, e);
        }
    }

    public static void main(String[] args) {
        LogFiles.install("agent");
        try {
            start(args);
        } catch (Exception e) { // e.g. AWTException from the capturer/injector: the packaged launcher has no console
            LOG.log(System.Logger.Level.ERROR, "Unexpected start failure", e);
            String reason = e.getMessage() != null ? e.getMessage() : e.getClass().getName();
            fail("MRemora Agent could not start: " + reason, 1);
        }
    }

    /** Starts the agent; the specific start errors exit through {@link #fail} with their own exit codes. */
    private static void start(String[] args) throws AWTException {
        if (GraphicsEnvironment.isHeadless()) {
            fail("MRemora agent needs a graphical desktop (headless environment detected).", 1);
            return;
        }
        int port;
        try {
            port = parsePort(args);
        } catch (IllegalArgumentException e) {
            fail(e.getMessage(), 2);
            return;
        }

        ScreenCapturer capturer = RobotScreenCapturer.forPrimaryScreen();
        InputInjector injector = RobotInputInjector.forPrimaryScreen();
        PairingCodes codes = new PairingCodes(Clock.systemUTC(), new SecureRandom());

        AtomicReference<AgentServer> serverRef = new AtomicReference<>();
        // Callbacks run on the EDT: shut down on a separate thread so the EDT is never blocked.
        AgentWindow window = AgentWindow.open(() -> Thread.ofPlatform().name("agent-quit").start(() -> {
            AgentServer server = serverRef.get();
            if (server != null) {
                server.close();
            }
            System.exit(0);
        }));
        AgentServer server = new AgentServer(AgentConfig.defaults(port), capturer, injector, codes, window);
        serverRef.set(server);
        int bound;
        try {
            bound = server.start();
        } catch (Exception e) { // Netty rethrows BindException unchecked
            window.close();
            fail("Cannot listen on port " + port + ": " + e.getMessage(), 1);
            return;
        }
        window.showAddresses(LocalAddresses.current(), bound);
        // Ctrl+C or a kill must still release buttons held by a remote drag; close() is idempotent and a hook
        // that races the quit thread waits for its close to finish (the quit thread exits only after closing).
        Runtime.getRuntime().addShutdownHook(Thread.ofPlatform().name("agent-shutdown").unstarted(server::close));
        System.out.println("MRemora agent listening on port " + bound);
    }
}

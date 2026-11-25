package pl.mtech.mremora.agent;

import java.awt.AWTException;
import java.awt.GraphicsEnvironment;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.concurrent.atomic.AtomicReference;

/** Runnable entry point of the agent: {@code AgentMain [--port N]}. */
public final class AgentMain {

    static final int DEFAULT_PORT = 7420;
    private static final String USAGE = "Usage: AgentMain [--port N]  (N in 1..65535, default " + DEFAULT_PORT + ")";

    private AgentMain() {}

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

    public static void main(String[] args) throws AWTException {
        if (GraphicsEnvironment.isHeadless()) {
            System.err.println("MRemora agent needs a graphical desktop (headless environment detected).");
            System.exit(1);
            return;
        }
        int port;
        try {
            port = parsePort(args);
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            System.exit(2);
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
            System.err.println("Cannot listen on port " + port + ": " + e.getMessage());
            System.exit(1);
            return;
        }
        // Ctrl+C or a kill must still release buttons held by a remote drag; close() is idempotent and a hook
        // that races the quit thread waits for its close to finish (the quit thread exits only after closing).
        Runtime.getRuntime().addShutdownHook(Thread.ofPlatform().name("agent-shutdown").unstarted(server::close));
        System.out.println("MRemora agent listening on port " + bound);
    }
}

package pl.mtech.mremora.viewer;

import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.Optional;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import pl.mtech.mremora.protocol.MouseButton.Button;

/**
 * The viewer window: a connect screen (host, port, pairing code) and, while connected, a {@link SessionView}.
 *
 * <p>Only one session at a time. All state is touched on the JavaFX application thread only.
 */
public final class ViewerApp extends Application {

    private static final Logger LOG = System.getLogger(ViewerApp.class.getName());
    private static final String TITLE = "MRemora";
    private static final int DEFAULT_PORT = 7420;
    private static final long STOP_TIMEOUT_SECONDS = 3;

    private final TextField hostField = new TextField();
    private final TextField portField = new TextField(Integer.toString(DEFAULT_PORT));
    private final TextField codeField = new TextField();
    private final javafx.scene.control.Button connectButton = new javafx.scene.control.Button("Connect");
    private final Label statusLabel = new Label();

    private Stage stage;
    private Scene scene;
    private VBox connectScreen;
    private Attempt attempt;
    private ViewerSession session;
    private SessionView sessionView;
    private volatile boolean stopped;
    /** An opened session not yet owned by the FX thread; whoever clears it (FX runnable or exit) owns it. */
    private final AtomicReference<ViewerSession> pending = new AtomicReference<>();

    @Override
    public void start(Stage primaryStage) {
        stage = primaryStage;
        connectScreen = buildConnectScreen();
        scene = new Scene(connectScreen, 960, 600);
        scene.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.ESCAPE && session != null) {
                e.consume();
                disconnect();
            }
        });
        stage.focusedProperty().addListener((obs, old, focused) -> {
            if (!focused && session != null) {
                session.input().releaseAll();
            }
        });
        stage.setTitle(TITLE);
        stage.setScene(scene);
        stage.show();
    }

    @Override
    public void stop() {
        stopped = true;
        if (sessionView != null) {
            sessionView.dispose();
        }
        ViewerSession owned = session != null ? session : pending.getAndSet(null);
        if (owned != null) {
            try {
                owned.disconnect().get(STOP_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (TimeoutException | ExecutionException e) {
                LOG.log(Level.INFO, "Disconnect on exit did not complete cleanly", e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** Maps a JavaFX mouse button to the protocol; buttons the protocol does not know are empty. */
    static Optional<Button> toProtocol(MouseButton button) {
        return switch (button) {
            case PRIMARY -> Optional.of(Button.LEFT);
            case MIDDLE -> Optional.of(Button.MIDDLE);
            case SECONDARY -> Optional.of(Button.RIGHT);
            case null, default -> Optional.empty();
        };
    }

    /**
     * Whether a decoded tile frame goes to the session view. Tiles of a stale attempt are ignored. Tiles of the
     * current attempt with no view yet throw: the session would ack a delta that was never applied and the image
     * would silently diverge, so {@link ViewerSession} ends it with {@code Bye("protocol error")} instead.
     */
    static boolean acceptsTiles(boolean currentAttempt, boolean hasView) {
        if (!currentAttempt) {
            return false;
        }
        if (!hasView) {
            throw new IllegalStateException("Tile frame for the current session before its view exists");
        }
        return true;
    }

    /** A status line for a failed connection attempt; completion/execution wrappers are unwrapped. */
    static String describeFailure(Throwable t) {
        Throwable cause = t;
        while ((cause instanceof CompletionException || cause instanceof ExecutionException)
                && cause.getCause() != null) {
            cause = cause.getCause();
        }
        String message = cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName();
        return switch (cause) {
            case AgentRejectedException rejected -> "Rejected by agent: " + rejected.reason();
            case TimeoutException _ -> "Agent did not respond";
            case IOException _ -> "Cannot connect: " + message;
            default -> "Error: " + message;
        };
    }

    private VBox buildConnectScreen() {
        hostField.setPromptText("e.g. 192.168.1.20");
        codeField.setPromptText("code shown by the agent");
        connectButton.setDefaultButton(true);
        connectButton.setOnAction(e -> connect());

        GridPane form = new GridPane();
        form.setHgap(8);
        form.setVgap(8);
        form.addRow(0, new Label("Host"), hostField);
        form.addRow(1, new Label("Port"), portField);
        form.addRow(2, new Label("Pairing code"), codeField);
        form.setAlignment(Pos.CENTER);

        VBox box = new VBox(12, form, connectButton, statusLabel);
        box.setAlignment(Pos.CENTER);
        box.setPadding(new Insets(24));
        return box;
    }

    private void connect() {
        if (attempt != null || session != null) {
            return;
        }
        String host = hostField.getText().strip();
        if (host.isEmpty()) {
            statusLabel.setText("Enter host");
            return;
        }
        int port;
        try {
            port = Integer.parseInt(portField.getText().strip());
        } catch (NumberFormatException e) {
            port = -1;
        }
        if (port < 1 || port > 65535) {
            statusLabel.setText("Invalid port");
            return;
        }

        Attempt current = new Attempt(host);
        attempt = current;
        connectButton.setDisable(true);
        statusLabel.setText("Connecting…");
        try {
            ViewerSession.open(host, port, codeField.getText(), Platform::runLater, current)
                    .whenComplete((opened, error) -> {
                        if (opened != null) {
                            pending.set(opened);
                            if (stopped && pending.compareAndSet(opened, null)) {
                                // The window is gone and stop() did not take it: close it here so Netty's non-daemon
                                // threads do not outlive the app. Its input was never used, so disconnect()'s
                                // releaseAll() sends nothing and touching it off the FX thread is safe.
                                opened.disconnect();
                                return;
                            }
                        }
                        Platform.runLater(() -> {
                            if (opened == null || pending.compareAndSet(opened, null)) {
                                onOpened(current, opened, error);
                            }
                        });
                    });
        } catch (RuntimeException e) {
            onOpened(current, null, e);
        }
    }

    private void onOpened(Attempt opening, ViewerSession opened, Throwable error) {
        if (opening != attempt) {
            return;
        }
        if (error != null) {
            attempt = null;
            connectButton.setDisable(false);
            statusLabel.setText(describeFailure(error));
            return;
        }
        session = opened;
        sessionView = new SessionView(opened, this::disconnect);
        scene.setRoot(sessionView.root());
        stage.setTitle(TITLE + " – " + opening.host);
        if (opening.closedReason != null) {
            endSession(opening.closedReason);
        }
    }

    private void disconnect() {
        if (session != null) {
            session.disconnect().exceptionally(e -> {
                LOG.log(Level.DEBUG, "Disconnect failed", e);
                return null;
            });
        }
    }

    private void endSession(String reason) {
        if (sessionView != null) {
            sessionView.dispose();
        }
        session = null;
        sessionView = null;
        attempt = null;
        scene.setRoot(connectScreen);
        stage.setTitle(TITLE);
        statusLabel.setText("Session ended: " + reason);
        codeField.clear();
        connectButton.setDisable(false);
    }

    /** One connection attempt and the listener of the session it may open; UI thread only. */
    private final class Attempt implements ViewerSession.Listener {

        private final String host;
        private String closedReason;

        Attempt(String host) {
            this.host = host;
        }

        @Override
        public void onFrame(DecodedFrame frame) {
            if (this == attempt && sessionView != null) {
                sessionView.onFrame(frame);
            }
        }

        @Override
        public void onTiles(DecodedTiles tiles) {
            if (acceptsTiles(this == attempt, sessionView != null)) {
                sessionView.onTiles(tiles);
            }
        }

        /** UDP rectangles may come before the view exists (before {@code TransportState}): not taken then. */
        @Override
        public boolean onUdpTiles(DecodedTiles tiles) {
            if (this != attempt || sessionView == null) {
                return false; // dropped: the agent resends these tiles
            }
            sessionView.onTiles(tiles);
            return true;
        }

        @Override
        public void onClosed(String reason) {
            if (this != attempt) {
                return;
            }
            if (session == null) {
                closedReason = reason; // closed before the opened session reached the UI thread
            } else {
                endSession(reason);
            }
        }
    }
}

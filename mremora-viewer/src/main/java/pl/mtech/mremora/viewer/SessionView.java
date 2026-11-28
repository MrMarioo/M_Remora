package pl.mtech.mremora.viewer;

import java.time.Clock;
import java.util.Locale;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.PauseTransition;
import javafx.animation.Timeline;
import javafx.beans.value.ChangeListener;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.stage.Window;
import javafx.util.Duration;
import pl.mtech.mremora.protocol.ScreenInfo;

/**
 * The session screen: the remote screen image scaled into the window, mouse input forwarded to the session, and an
 * overlay with statistics and a Disconnect button.
 *
 * <p>JavaFX application thread only. {@link #dispose()} must be called when the session ends.
 */
final class SessionView {

    private static final Duration OVERLAY_REFRESH = Duration.millis(500);
    /** Quiet time after the last resize before the agent is asked for a new stream size. */
    private static final Duration STREAM_CONFIG_DELAY = Duration.millis(250);

    private final ViewerSession session;
    private final FrameStats stats = new FrameStats(Clock.systemUTC());
    private final Pane imagePane = new Pane();
    private final ImageView imageView = new ImageView();
    private final Label statsLabel = new Label();
    private final StackPane root;
    private final Timeline overlayRefresh;
    private final PauseTransition streamConfigDelay = new PauseTransition(STREAM_CONFIG_DELAY);
    private WritableImage image;
    /** Re-reports the view when the window moves to a screen with another scale (HiDPI). */
    private final ChangeListener<Number> scaleListener = (obs, old, now) -> paneResized();
    private final ChangeListener<Window> windowListener = (obs, old, now) -> watchScale(now);
    private Scene watchedScene;
    private Window watchedWindow;
    /** The view size was reported to the session at least once; later changes are debounced. */
    private boolean viewReported;

    SessionView(ViewerSession session, Runnable onDisconnect) {
        this.session = session;

        imageView.setManaged(false);
        imageView.setPreserveRatio(false);
        imageView.setSmooth(true);
        imagePane.getChildren().add(imageView);
        imagePane.setStyle("-fx-background-color: black;");
        imagePane.setMinSize(0, 0);
        imagePane.widthProperty().addListener((obs, old, now) -> paneResized());
        imagePane.heightProperty().addListener((obs, old, now) -> paneResized());
        streamConfigDelay.setOnFinished(e -> reportView());
        imagePane.sceneProperty().addListener((obs, old, now) -> watchScene(now));
        installMouseHandlers();
        relayout(); // the viewport comes from the agent's screen, so input works before the first frame

        statsLabel.setMouseTransparent(true);
        statsLabel.setStyle("-fx-text-fill: white; -fx-background-color: rgba(0,0,0,0.6); -fx-padding: 4 8;");
        Button disconnect = new Button("Disconnect");
        disconnect.setFocusTraversable(false);
        disconnect.setOnAction(e -> onDisconnect.run());
        HBox overlay = new HBox(8, statsLabel, disconnect);
        overlay.setAlignment(Pos.CENTER_LEFT);
        overlay.setPadding(new Insets(8));
        overlay.setPickOnBounds(false);
        overlay.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        StackPane.setAlignment(overlay, Pos.TOP_LEFT);

        root = new StackPane(imagePane, overlay);
        root.setMinSize(0, 0);

        updateOverlay();
        overlayRefresh = new Timeline(new KeyFrame(OVERLAY_REFRESH, e -> updateOverlay()));
        overlayRefresh.setCycleCount(Animation.INDEFINITE);
        overlayRefresh.play();
    }

    Parent root() {
        return root;
    }

    /** Shows a decoded frame (v1); a new image buffer is created only when the resolution changes. */
    void onFrame(DecodedFrame frame) {
        int w = frame.width();
        int h = frame.height();
        ensureImage(w, h);
        // Premultiplied is valid: JPEG pixels are always opaque (alpha 0xFF), so both forms are identical, and the
        // premultiplied format is the one JavaFX converts to its native BGRA_PRE cheaply.
        image.getPixelWriter().setPixels(0, 0, w, h, PixelFormat.getIntArgbPreInstance(), frame.argb(), 0, w);
        stats.record(frame.encodedBytes());
    }

    /**
     * Applies a tile frame (v2) or a UDP batch of changed rectangles (v3) to the stream image; a new buffer is
     * created when the stream size changes (the agent then sends a full frame). The overlay's "fps" counts these
     * calls, so on the UDP path it is applied batches of changes per second, not complete frames.
     */
    void onTiles(DecodedTiles tiles) {
        ensureImage(tiles.streamWidth(), tiles.streamHeight());
        var writer = image.getPixelWriter();
        // Premultiplied is valid for the same reason as in onFrame: all decoded pixels are opaque.
        var format = PixelFormat.getIntArgbPreInstance();
        for (DecodedTiles.Patch p : tiles.patches()) {
            writer.setPixels(p.x(), p.y(), p.width(), p.height(), format, p.argb(), 0, p.width());
        }
        stats.record(tiles.encodedBytes());
    }

    /** Stops the overlay refresh and a pending stream size request; the view is unusable afterwards. */
    void dispose() {
        overlayRefresh.stop();
        streamConfigDelay.stop();
        watchScene(null);
    }

    private void watchScene(Scene scene) {
        if (watchedScene != null) {
            watchedScene.windowProperty().removeListener(windowListener);
        }
        watchedScene = scene;
        if (scene != null) {
            scene.windowProperty().addListener(windowListener);
        }
        watchScale(scene != null ? scene.getWindow() : null);
    }

    private void watchScale(Window window) {
        if (window == watchedWindow) {
            return;
        }
        if (watchedWindow != null) {
            watchedWindow.outputScaleXProperty().removeListener(scaleListener);
        }
        watchedWindow = window;
        if (window != null) {
            window.outputScaleXProperty().addListener(scaleListener);
        }
    }

    private void ensureImage(int w, int h) {
        if (image == null || (int) image.getWidth() != w || (int) image.getHeight() != h) {
            image = new WritableImage(w, h);
            imageView.setImage(image);
        }
    }

    /** Relayouts at once; tells the session the new size at once the first time, then 250 ms after the last change. */
    private void paneResized() {
        relayout();
        if (!viewReported) {
            reportView();
        } else {
            streamConfigDelay.playFromStart();
        }
    }

    private void reportView() {
        double w = imagePane.getWidth();
        double h = imagePane.getHeight();
        if (w <= 0 || h <= 0) {
            return; // not laid out yet: the first real size is still reported without delay
        }
        viewReported = true;
        session.viewChanged(w, h, outputScale());
    }

    /** Physical pixels per layout unit of the window's screen (HiDPI), {@code 1} before the view is shown. */
    private double outputScale() {
        Scene scene = imagePane.getScene();
        Window window = scene != null ? scene.getWindow() : null;
        return window != null ? window.getOutputScaleX() : 1.0;
    }

    private void installMouseHandlers() {
        InputTranslator input = session.input();
        imagePane.addEventHandler(MouseEvent.MOUSE_MOVED, e -> input.moved(e.getX(), e.getY()));
        imagePane.addEventHandler(MouseEvent.MOUSE_DRAGGED, e -> input.moved(e.getX(), e.getY()));
        imagePane.addEventHandler(MouseEvent.MOUSE_PRESSED, e ->
                ViewerApp.toProtocol(e.getButton()).ifPresent(b -> input.pressed(b, e.getX(), e.getY())));
        imagePane.addEventHandler(MouseEvent.MOUSE_RELEASED, e ->
                ViewerApp.toProtocol(e.getButton()).ifPresent(b -> input.released(b, e.getX(), e.getY())));
        imagePane.addEventHandler(ScrollEvent.SCROLL, e -> input.scrolled(e.getDeltaY(), e.getMultiplierY()));
    }

    /**
     * Fits the agent's screen into the pane and gives the translator the same viewport; the stream image, whatever
     * its size, is stretched into that rectangle, so the mouse maps to physical screen pixels as before.
     */
    private void relayout() {
        ScreenInfo screen = session.screenInfo();
        Viewport viewport = Viewport.fit(screen.width(), screen.height(), imagePane.getWidth(), imagePane.getHeight());
        imageView.setFitWidth(viewport.width());
        imageView.setFitHeight(viewport.height());
        imageView.setLayoutX(viewport.x());
        imageView.setLayoutY(viewport.y());
        imageView.setVisible(!viewport.isEmpty());
        session.input().setViewport(viewport);
    }

    private void updateOverlay() {
        FrameStats.Snapshot snapshot = stats.snapshot();
        int w;
        int h;
        if (image != null) {
            w = (int) image.getWidth();
            h = (int) image.getHeight();
        } else {
            ScreenInfo screen = session.screenInfo();
            w = screen.width();
            h = screen.height();
        }
        statsLabel.setText(String.format(Locale.ROOT, "%.0f fps · %.1f Mbit/s · %d×%d",
                snapshot.fps(), snapshot.megabitsPerSecond(), w, h));
    }
}

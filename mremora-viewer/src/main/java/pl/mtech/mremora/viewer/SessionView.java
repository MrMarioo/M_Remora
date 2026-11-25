package pl.mtech.mremora.viewer;

import java.time.Clock;
import java.util.Locale;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Parent;
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

    private final ViewerSession session;
    private final FrameStats stats = new FrameStats(Clock.systemUTC());
    private final Pane imagePane = new Pane();
    private final ImageView imageView = new ImageView();
    private final Label statsLabel = new Label();
    private final StackPane root;
    private final Timeline overlayRefresh;
    private WritableImage image;

    SessionView(ViewerSession session, Runnable onDisconnect) {
        this.session = session;

        imageView.setManaged(false);
        imageView.setPreserveRatio(false);
        imageView.setSmooth(true);
        imagePane.getChildren().add(imageView);
        imagePane.setStyle("-fx-background-color: black;");
        imagePane.setMinSize(0, 0);
        imagePane.widthProperty().addListener((obs, old, now) -> relayout());
        imagePane.heightProperty().addListener((obs, old, now) -> relayout());
        installMouseHandlers();

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

    /** Shows a decoded frame; a new image buffer is created only when the resolution changes. */
    void onFrame(DecodedFrame frame) {
        int w = frame.width();
        int h = frame.height();
        if (image == null || (int) image.getWidth() != w || (int) image.getHeight() != h) {
            image = new WritableImage(w, h);
            imageView.setImage(image);
            relayout();
        }
        // Premultiplied is valid: JPEG pixels are always opaque (alpha 0xFF), so both forms are identical, and the
        // premultiplied format is the one JavaFX converts to its native BGRA_PRE cheaply.
        image.getPixelWriter().setPixels(0, 0, w, h, PixelFormat.getIntArgbPreInstance(), frame.argb(), 0, w);
        stats.record(frame.encodedBytes());
    }

    /** Stops the overlay refresh; the view is unusable afterwards. */
    void dispose() {
        overlayRefresh.stop();
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

    /** Fits the image into the pane and gives the translator the same viewport. */
    private void relayout() {
        if (image == null) {
            return; // no viewport until the first frame, so the translator ignores input until then
        }
        Viewport viewport = Viewport.fit((int) image.getWidth(), (int) image.getHeight(),
                imagePane.getWidth(), imagePane.getHeight());
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

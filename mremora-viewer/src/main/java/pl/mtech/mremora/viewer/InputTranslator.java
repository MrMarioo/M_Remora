package pl.mtech.mremora.viewer;

import java.util.EnumSet;
import java.util.function.Consumer;
import pl.mtech.mremora.protocol.Message;
import pl.mtech.mremora.protocol.MouseButton;
import pl.mtech.mremora.protocol.MouseMove;
import pl.mtech.mremora.protocol.MouseWheel;

/**
 * Translates mouse events of the viewer window into protocol messages for the agent.
 *
 * <p>View coordinates are mapped through the current {@link Viewport} to agent screen pixels. While a button is held,
 * positions outside the image are clamped to its edge so that drags and releases are never lost. Plain moves skip a
 * point equal to the last one sent, but a press or release is always preceded by a {@link MouseMove}: the agent
 * presses at the current OS cursor, which its local user may have moved since. Single-threaded (JavaFX application
 * thread); not thread-safe.
 */
final class InputTranslator {

    private static final double DEFAULT_NOTCH_PIXELS = 40;

    private final Consumer<Message> out;
    private final EnumSet<MouseButton.Button> held =
            EnumSet.noneOf(MouseButton.Button.class);
    private Viewport viewport;
    private ImagePoint lastSent;
    private double wheelResidual;

    InputTranslator(Consumer<Message> out) {
        this.out = out;
    }

    /** Sets the current viewport; {@code null} or an empty one makes all events except {@link #releaseAll()} ignored. */
    void setViewport(Viewport viewport) {
        this.viewport = viewport;
    }

    void moved(double vx, double vy) {
        if (!active()) {
            return;
        }
        if (held.isEmpty()) {
            viewport.toImage(vx, vy).ifPresent(this::sendMove);
        } else {
            sendMove(viewport.toImageClamped(vx, vy));
        }
    }

    void pressed(MouseButton.Button button, double vx, double vy) {
        if (!active() || held.contains(button)) {
            return;
        }
        viewport.toImage(vx, vy).ifPresent(point -> {
            sendMoveAlways(point);
            held.add(button);
            out.accept(new MouseButton(button, true));
        });
    }

    void released(MouseButton.Button button, double vx, double vy) {
        if (!held.contains(button)) {
            return;
        }
        if (active()) {
            sendMoveAlways(viewport.toImageClamped(vx, vy));
        }
        held.remove(button);
        out.accept(new MouseButton(button, false));
    }

    /** Accumulates scroll deltas; JavaFX positive deltaY means up, the protocol's positive notches mean down. */
    void scrolled(double deltaY, double multiplierY) {
        if (!active() || !Double.isFinite(deltaY)) {
            return;
        }
        wheelResidual += -deltaY / (multiplierY > 0 ? multiplierY : DEFAULT_NOTCH_PIXELS);
        int whole = (int) wheelResidual;
        if (whole != 0) {
            wheelResidual -= whole;
            out.accept(new MouseWheel(whole));
        }
    }

    /** Releases every held button (in LEFT, MIDDLE, RIGHT order) and clears the state, e.g. on focus loss. */
    void releaseAll() {
        for (var button : held) {
            out.accept(new MouseButton(button, false));
        }
        held.clear();
    }

    private boolean active() {
        return viewport != null && !viewport.isEmpty();
    }

    private void sendMove(ImagePoint point) {
        if (!point.equals(lastSent)) {
            sendMoveAlways(point);
        }
    }

    private void sendMoveAlways(ImagePoint point) {
        lastSent = point;
        out.accept(new MouseMove(point.x(), point.y()));
    }
}

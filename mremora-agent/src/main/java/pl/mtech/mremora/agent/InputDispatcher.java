package pl.mtech.mremora.agent;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import pl.mtech.mremora.protocol.Message;
import pl.mtech.mremora.protocol.MouseButton;
import pl.mtech.mremora.protocol.MouseMove;
import pl.mtech.mremora.protocol.MouseWheel;

/**
 * Routes input messages to an {@link InputInjector} and tracks held buttons so they can be
 * released when the session ends.
 *
 * <p>Not thread-safe: must be called from a single thread (the session thread).
 */
public final class InputDispatcher {

    private final InputInjector injector;
    private final Set<MouseButton.Button> held = EnumSet.noneOf(MouseButton.Button.class);

    public InputDispatcher(InputInjector injector) {
        this.injector = Objects.requireNonNull(injector, "injector");
    }

    /**
     * @throws IllegalArgumentException if the message is not an input message
     */
    public void dispatch(Message message) {
        switch (message) {
            case MouseMove m -> injector.move(m.x(), m.y());
            case MouseButton b -> {
                if (b.pressed()) {
                    held.add(b.button());
                    injector.press(b.button());
                } else {
                    injector.release(b.button());
                    held.remove(b.button());
                }
            }
            case MouseWheel w -> injector.wheel(w.notches());
            default -> throw new IllegalArgumentException("Not an input message: " + message);
        }
    }

    /** Releases every button still held, so a dropped session cannot leave one stuck down. */
    public void releaseAll() {
        for (var button : held) {
            injector.release(button);
        }
        held.clear();
    }
}

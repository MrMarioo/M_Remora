package pl.mtech.mremora.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import pl.mtech.mremora.protocol.FrameUpdate;
import pl.mtech.mremora.protocol.Heartbeat;
import pl.mtech.mremora.protocol.Hello;
import pl.mtech.mremora.protocol.MouseButton;
import pl.mtech.mremora.protocol.MouseButton.Button;
import pl.mtech.mremora.protocol.MouseMove;
import pl.mtech.mremora.protocol.MouseWheel;

class InputDispatcherTest {

    private final RecordingInjector injector = new RecordingInjector();
    private final InputDispatcher dispatcher = new InputDispatcher(injector);

    @Test
    void movesPointer() {
        dispatcher.dispatch(new MouseMove(10, 20));

        assertThat(injector.calls).containsExactly("move 10,20");
    }

    @Test
    void pressesAndReleases() {
        dispatcher.dispatch(new MouseButton(Button.LEFT, true));
        dispatcher.dispatch(new MouseButton(Button.LEFT, false));

        assertThat(injector.calls).containsExactly("press LEFT", "release LEFT");
    }

    @Test
    void scrollsWheel() {
        dispatcher.dispatch(new MouseWheel(-3));

        assertThat(injector.calls).containsExactly("wheel -3");
    }

    @Test
    void rejectsNonInputMessages() {
        assertThatThrownBy(() -> dispatcher.dispatch(new Heartbeat()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> dispatcher.dispatch(new Hello(1, Hello.Role.VIEWER, "123456")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> dispatcher.dispatch(new FrameUpdate(1, FrameUpdate.Format.JPEG, new byte[0])))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(injector.calls).isEmpty();
    }

    @Test
    void releaseAllReleasesHeldButtons() {
        dispatcher.dispatch(new MouseButton(Button.LEFT, true));
        dispatcher.dispatch(new MouseButton(Button.RIGHT, true));
        injector.calls.clear();

        dispatcher.releaseAll();

        assertThat(injector.calls).containsExactlyInAnyOrder("release LEFT", "release RIGHT");
    }

    @Test
    void releaseAllSkipsAlreadyReleased() {
        dispatcher.dispatch(new MouseButton(Button.LEFT, true));
        dispatcher.dispatch(new MouseButton(Button.LEFT, false));
        injector.calls.clear();

        dispatcher.releaseAll();

        assertThat(injector.calls).isEmpty();
    }

    @Test
    void releaseAllIsIdempotent() {
        dispatcher.dispatch(new MouseButton(Button.MIDDLE, true));
        dispatcher.releaseAll();
        injector.calls.clear();

        dispatcher.releaseAll();

        assertThat(injector.calls).isEmpty();
    }

    @Test
    void throwingReleaseKeepsButtonTrackedForReleaseAll() {
        dispatcher.dispatch(new MouseButton(Button.LEFT, true));
        injector.failNextRelease = true;

        assertThatThrownBy(() -> dispatcher.dispatch(new MouseButton(Button.LEFT, false)))
                .isInstanceOf(IllegalStateException.class);
        injector.calls.clear();
        dispatcher.releaseAll();

        assertThat(injector.calls).containsExactly("release LEFT");
    }

    @Test
    void rejectsNullInjector() {
        assertThatThrownBy(() -> new InputDispatcher(null)).isInstanceOf(NullPointerException.class);
    }

    private static final class RecordingInjector implements InputInjector {
        final List<String> calls = new ArrayList<>();
        boolean failNextRelease;

        @Override
        public void move(int x, int y) {
            calls.add("move " + x + "," + y);
        }

        @Override
        public void press(Button b) {
            calls.add("press " + b);
        }

        @Override
        public void release(Button b) {
            if (failNextRelease) {
                failNextRelease = false;
                throw new IllegalStateException("boom");
            }
            calls.add("release " + b);
        }

        @Override
        public void wheel(int notches) {
            calls.add("wheel " + notches);
        }
    }
}

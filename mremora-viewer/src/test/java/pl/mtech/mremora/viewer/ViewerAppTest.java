package pl.mtech.mremora.viewer;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.ConnectException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import javafx.scene.input.MouseButton;
import org.junit.jupiter.api.Test;
import pl.mtech.mremora.protocol.MouseButton.Button;

/** Static helpers of {@link ViewerApp}; the JavaFX toolkit is never started. */
class ViewerAppTest {

    @Test
    void mapsFxButtons() {
        assertThat(ViewerApp.toProtocol(MouseButton.PRIMARY)).contains(Button.LEFT);
        assertThat(ViewerApp.toProtocol(MouseButton.MIDDLE)).contains(Button.MIDDLE);
        assertThat(ViewerApp.toProtocol(MouseButton.SECONDARY)).contains(Button.RIGHT);
        assertThat(ViewerApp.toProtocol(MouseButton.BACK)).isEmpty();
        assertThat(ViewerApp.toProtocol(MouseButton.FORWARD)).isEmpty();
        assertThat(ViewerApp.toProtocol(MouseButton.NONE)).isEmpty();
    }

    @Test
    void describesFailures() {
        assertThat(ViewerApp.describeFailure(new CompletionException(new AgentRejectedException("busy"))))
                .isEqualTo("Rejected by agent: busy");
        assertThat(ViewerApp.describeFailure(new TimeoutException())).isEqualTo("Agent did not respond");
        assertThat(ViewerApp.describeFailure(new ConnectException("Connection refused")))
                .isEqualTo("Cannot connect: Connection refused");
    }

    @Test
    void unwrapsNestedWrappers() {
        Throwable nested = new CompletionException(new ExecutionException(new TimeoutException("late")));
        assertThat(ViewerApp.describeFailure(nested)).isEqualTo("Agent did not respond");
    }

    @Test
    void usesClassNameWhenMessageIsMissing() {
        assertThat(ViewerApp.describeFailure(new IllegalStateException())).isEqualTo("Error: IllegalStateException");
        assertThat(ViewerApp.describeFailure(new IOException())).isEqualTo("Cannot connect: IOException");
        assertThat(ViewerApp.describeFailure(new RuntimeException("boom"))).isEqualTo("Error: boom");
    }
}

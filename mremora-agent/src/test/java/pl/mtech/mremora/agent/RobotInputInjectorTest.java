package pl.mtech.mremora.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.event.InputEvent;
import org.junit.jupiter.api.Test;
import pl.mtech.mremora.protocol.MouseButton;

class RobotInputInjectorTest {

    @Test
    void buttonMaskMapsAllButtons() {
        assertThat(RobotInputInjector.buttonMask(MouseButton.Button.LEFT)).isEqualTo(InputEvent.BUTTON1_DOWN_MASK);
        assertThat(RobotInputInjector.buttonMask(MouseButton.Button.MIDDLE)).isEqualTo(InputEvent.BUTTON2_DOWN_MASK);
        assertThat(RobotInputInjector.buttonMask(MouseButton.Button.RIGHT)).isEqualTo(InputEvent.BUTTON3_DOWN_MASK);
    }
}

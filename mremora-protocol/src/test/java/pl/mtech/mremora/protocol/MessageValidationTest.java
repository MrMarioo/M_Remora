package pl.mtech.mremora.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static pl.mtech.mremora.protocol.MessageCodecTest.buffer;

import org.junit.jupiter.api.Test;

class MessageValidationTest {

    @Test
    void screenInfoRequiresPositiveDimensionsAndScale() {
        assertThatThrownBy(() -> new ScreenInfo(0, 1080, 100)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ScreenInfo(1920, -1, 100)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ScreenInfo(1920, 1080, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void invalidValuesFromTheWireBecomeProtocolErrors() {
        // SCREEN_INFO with width 0
        var in = buffer(0x00, 0x00, 0x00, 0x0C, 0x02, 0x00,
                0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x04, 0x38, 0x00, 0x64);

        assertThatThrownBy(() -> new MessageCodec().decode(in)).isInstanceOf(ProtocolException.class);
    }

    @Test
    void helloRequiresSupportedVersionRange() {
        assertThatThrownBy(() -> new Hello(0, Hello.Role.AGENT, "")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Hello(65_536, Hello.Role.AGENT, "")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nullFieldsAreRejectedAtConstruction() {
        assertThatThrownBy(() -> new Hello(1, null, "")).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Hello(1, Hello.Role.AGENT, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new MouseButton(null, true)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Bye(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void frameUpdateIsNotAffectedByLaterChangesToTheSourceArray() {
        byte[] source = {1, 2, 3};
        FrameUpdate update = new FrameUpdate(1, FrameUpdate.Format.JPEG, source);

        source[0] = 99;
        update.data()[1] = 99;

        assertThat(update.data()).containsExactly(1, 2, 3);
    }
}

package pl.mtech.mremora.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static pl.mtech.mremora.protocol.MessageCodecTest.buffer;

import java.util.List;
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

    private static TileUpdate.Rect rect(int x, int y, int w, int h) {
        return rect(x, y, w, h, new byte[] {0});
    }

    private static TileUpdate.Rect rect(int x, int y, int w, int h, byte[] data) {
        return new TileUpdate.Rect(x, y, w, h, TileUpdate.Encoding.LOSSLESS, data);
    }

    @Test
    void rectMayTouchTheRightAndBottomEdge() {
        new TileUpdate(1, 64, 32, true, 0, List.of(rect(62, 31, 2, 1)));
    }

    @Test
    void rectPastTheEdgeIsRejected() {
        assertThatThrownBy(() -> new TileUpdate(1, 64, 32, true, 0, List.of(rect(63, 0, 2, 1))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TileUpdate(1, 64, 32, true, 0, List.of(rect(0, 32, 1, 1))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void emptyRectIsRejected() {
        assertThatThrownBy(() -> rect(0, 0, 0, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> rect(0, 0, 1, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rectCoordinatesOutsideU16AreRejected() {
        assertThatThrownBy(() -> rect(-1, 0, 1, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> rect(0, 65_536, 1, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> rect(0, 0, 65_536, 1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void streamConfigNeedsBothOrNeitherZero() {
        assertThatThrownBy(() -> new StreamConfig(0, 900)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new StreamConfig(1600, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new StreamConfig(65_536, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThat(new StreamConfig(0, 0)).isEqualTo(StreamConfig.FULL_RESOLUTION);
        new StreamConfig(1, 1);
    }

    @Test
    void latencyAboveU16IsRejected() {
        assertThatThrownBy(() -> new TileUpdate(1, 64, 32, true, 65_536, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TileUpdate(1, 64, 32, true, -1, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void streamSizeOutsideU16IsRejected() {
        assertThatThrownBy(() -> new TileUpdate(1, 0, 32, true, 0, List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TileUpdate(1, 64, 65_536, true, 0, List.of())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rectDataIsNotCopied() {
        byte[] d = {1};

        assertThat(rect(0, 0, 1, 1, d).data()).isSameAs(d);
    }

    @Test
    void rectEqualityComparesDataContent() {
        assertThat(rect(0, 0, 1, 1, new byte[] {1})).isEqualTo(rect(0, 0, 1, 1, new byte[] {1}))
                .hasSameHashCodeAs(rect(0, 0, 1, 1, new byte[] {1}));
        assertThat(rect(0, 0, 1, 1, new byte[] {1})).isNotEqualTo(rect(0, 0, 1, 1, new byte[] {2}));
        assertThat(rect(0, 0, 1, 1, new byte[] {1, 2}).toString()).contains("2 bytes");
    }

    @Test
    void tileUpdateCopiesTheRectList() {
        var rects = new java.util.ArrayList<>(List.of(rect(0, 0, 1, 1)));
        TileUpdate update = new TileUpdate(1, 64, 32, true, 0, rects);

        rects.clear();

        assertThat(update.rects()).hasSize(1);
    }

    @Test
    void nullRectFieldsAreRejected() {
        assertThatThrownBy(() -> new TileUpdate.Rect(0, 0, 1, 1, null, new byte[0])).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new TileUpdate.Rect(0, 0, 1, 1, TileUpdate.Encoding.COPY, null)).isInstanceOf(NullPointerException.class);
    }
}

package pl.mtech.mremora.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static pl.mtech.mremora.protocol.MessageCodecTest.buffer;
import static pl.mtech.mremora.protocol.MessageCodecTest.bytes;

import java.nio.ByteBuffer;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Pins the exact bytes of every message. The expected frames are written by hand:
 * if one of these fails, the wire format changed and old clients will break.
 */
class MessageWireFormatTest {

    private final MessageCodec codec = new MessageCodec();

    static Stream<Arguments> messages() {
        return Stream.of(
                Arguments.of(new Hello(1, Hello.Role.VIEWER, "AB12"), new int[] {
                        0x00, 0x00, 0x00, 0x0B, 0x01, 0x00,
                        0x00, 0x01,                   // protocol version 1
                        0x02,                         // role VIEWER
                        0x00, 0x04, 0x41, 0x42, 0x31, 0x32}), // "AB12"
                Arguments.of(new Hello(3, Hello.Role.AGENT, ""), new int[] {
                        0x00, 0x00, 0x00, 0x07, 0x01, 0x00,
                        0x00, 0x03, 0x01, 0x00, 0x00}),
                Arguments.of(new ScreenInfo(1920, 1080, 125), new int[] {
                        0x00, 0x00, 0x00, 0x0C, 0x02, 0x00,
                        0x00, 0x00, 0x07, 0x80,       // 1920
                        0x00, 0x00, 0x04, 0x38,       // 1080
                        0x00, 0x7D}),                 // 125 %
                Arguments.of(new FrameUpdate(7, FrameUpdate.Format.JPEG, new byte[] {(byte) 0xCA, (byte) 0xFE}), new int[] {
                        0x00, 0x00, 0x00, 0x0D, 0x10, 0x00,
                        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x07, // sequence 7
                        0x01,                         // JPEG
                        0xCA, 0xFE}),                 // data = rest of payload
                Arguments.of(new MouseButton(MouseButton.Button.RIGHT, true), new int[] {
                        0x00, 0x00, 0x00, 0x04, 0x21, 0x00, 0x03, 0x01}),
                Arguments.of(new MouseButton(MouseButton.Button.LEFT, false), new int[] {
                        0x00, 0x00, 0x00, 0x04, 0x21, 0x00, 0x01, 0x00}),
                Arguments.of(new MouseWheel(-3), new int[] {
                        0x00, 0x00, 0x00, 0x06, 0x22, 0x00, 0xFF, 0xFF, 0xFF, 0xFD}),
                Arguments.of(new Heartbeat(), new int[] {
                        0x00, 0x00, 0x00, 0x02, 0x30, 0x00}),
                Arguments.of(new Bye("koniec ż"), new int[] {
                        0x00, 0x00, 0x00, 0x0D, 0x31, 0x00,
                        0x00, 0x09, 0x6B, 0x6F, 0x6E, 0x69, 0x65, 0x63, 0x20, 0xC5, 0xBC})); // UTF-8
    }

    @ParameterizedTest
    @MethodSource("messages")
    void encodesToExactBytes(Message message, int[] frame) {
        assertThat(bytes(codec.encode(message))).containsExactly(frame);
    }

    @ParameterizedTest
    @MethodSource("messages")
    void decodesExactBytes(Message message, int[] frame) {
        ByteBuffer in = buffer(frame);

        assertThat(codec.decode(in)).contains(message);
        assertThat(in.hasRemaining()).isFalse();
    }

    @Test
    void helloFromANewerVersionWithExtraFieldsStillDecodes() {
        // Hello v2, role AGENT, code "" + 3 bytes of fields this version does not know yet.
        // Hello must stay readable across versions, otherwise negotiation is impossible.
        ByteBuffer in = buffer(0x00, 0x00, 0x00, 0x0A, 0x01, 0x00,
                0x00, 0x02, 0x01, 0x00, 0x00,
                0xAA, 0xBB, 0xCC);

        assertThat(codec.decode(in)).contains(new Hello(2, Hello.Role.AGENT, ""));
        assertThat(in.hasRemaining()).isFalse();
    }

    @Test
    void rejectsFrameWithReservedFlagsSet() {
        // HEARTBEAT with flags = 0x01; a flag we do not understand could change how the payload must be read
        ByteBuffer in = buffer(0x00, 0x00, 0x00, 0x02, 0x30, 0x01);

        assertThatThrownBy(() -> codec.decode(in))
                .isInstanceOf(ProtocolException.class)
                .hasMessageContaining("flags");
    }

    @Test
    void rejectsUnknownMouseButton() {
        ByteBuffer in = buffer(0x00, 0x00, 0x00, 0x04, 0x21, 0x00, 0x09, 0x01);

        assertThatThrownBy(() -> codec.decode(in)).isInstanceOf(ProtocolException.class)
                .hasMessageContaining("button");
    }

    @Test
    void rejectsPressedFlagOtherThanZeroOrOne() {
        ByteBuffer in = buffer(0x00, 0x00, 0x00, 0x04, 0x21, 0x00, 0x01, 0x02);

        assertThatThrownBy(() -> codec.decode(in)).isInstanceOf(ProtocolException.class)
                .hasMessageContaining("pressed");
    }

    @Test
    void rejectsUnknownRole() {
        ByteBuffer in = buffer(0x00, 0x00, 0x00, 0x07, 0x01, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00);

        assertThatThrownBy(() -> codec.decode(in)).isInstanceOf(ProtocolException.class)
                .hasMessageContaining("role");
    }

    @Test
    void rejectsUnknownFrameFormat() {
        ByteBuffer in = buffer(0x00, 0x00, 0x00, 0x0B, 0x10, 0x00,
                0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x01, 0x05);

        assertThatThrownBy(() -> codec.decode(in)).isInstanceOf(ProtocolException.class)
                .hasMessageContaining("format");
    }

    @Test
    void rejectsMalformedUtf8() {
        ByteBuffer in = buffer(0x00, 0x00, 0x00, 0x05, 0x31, 0x00, 0x00, 0x01, 0xFF);

        assertThatThrownBy(() -> codec.decode(in)).isInstanceOf(ProtocolException.class)
                .hasMessageContaining("UTF-8");
    }

    @Test
    void rejectsStringLongerThanPayload() {
        // Bye claims a 16-byte reason but carries 1 byte
        ByteBuffer in = buffer(0x00, 0x00, 0x00, 0x05, 0x31, 0x00, 0x00, 0x10, 0x41);

        assertThatThrownBy(() -> codec.decode(in)).isInstanceOf(ProtocolException.class)
                .hasMessageContaining("too short");
    }

    @Test
    void refusesToEncodeStringThatDoesNotFitU16Length() {
        Bye bye = new Bye("x".repeat(65_536));

        assertThatThrownBy(() -> codec.encode(bye)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void refusesToEncodeFrameAboveSizeLimit() {
        FrameUpdate huge = new FrameUpdate(1, FrameUpdate.Format.JPEG, new byte[MessageCodec.MAX_FRAME_LENGTH]);

        assertThatThrownBy(() -> codec.encode(huge)).isInstanceOf(IllegalArgumentException.class);
    }
}

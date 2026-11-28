package pl.mtech.mremora.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.ByteBuffer;
import org.junit.jupiter.api.Test;

class MessageCodecTest {

    private final MessageCodec codec = new MessageCodec();

    @Test
    void encodesMouseMoveAsLengthPrefixedBigEndianFrame() {
        ByteBuffer frame = codec.encode(new MouseMove(300, -2));

        assertThat(bytes(frame)).containsExactly(
                0x00, 0x00, 0x00, 0x0A,       // length of everything after this field: 1 + 1 + 8
                0x20,                         // type: MOUSE_MOVE
                0x00,                         // flags
                0x00, 0x00, 0x01, 0x2C,       // x = 300
                0xFF, 0xFF, 0xFF, 0xFE);      // y = -2
    }

    @Test
    void udpSetupAndTransportStateSurviveARoundTrip() {
        byte[] key = new byte[32];
        java.util.Arrays.fill(key, (byte) 0xA5);
        for (Message message : new Message[] {
                new UdpSetup(65_535, -1, key), new TransportState(TransportState.Mode.UDP, 65_535)}) {
            assertThat(codec.decode(codec.encode(message))).contains(message);
        }
    }

    @Test
    void decodesMouseMoveFromHandWrittenFrame() {
        ByteBuffer in = buffer(
                0x00, 0x00, 0x00, 0x0A, 0x20, 0x00,
                0x00, 0x00, 0x01, 0x2C,
                0xFF, 0xFF, 0xFF, 0xFE);

        assertThat(codec.decode(in)).contains(new MouseMove(300, -2));
        assertThat(in.hasRemaining()).isFalse();
    }

    @Test
    void returnsEmptyAndConsumesNothingWhenFrameIsIncomplete() {
        // full MouseMove frame minus its last byte
        ByteBuffer in = buffer(
                0x00, 0x00, 0x00, 0x0A, 0x20, 0x00,
                0x00, 0x00, 0x01, 0x2C,
                0xFF, 0xFF, 0xFF);

        assertThat(codec.decode(in)).isEmpty();
        assertThat(in.position()).isZero();
    }

    @Test
    void returnsEmptyWhenEvenTheLengthFieldIsIncomplete() {
        ByteBuffer in = buffer(0x00, 0x00, 0x00);

        assertThat(codec.decode(in)).isEmpty();
        assertThat(in.position()).isZero();
    }

    @Test
    void decodesConsecutiveFramesOneAtATime() {
        ByteBuffer in = buffer(
                0x00, 0x00, 0x00, 0x0A, 0x20, 0x00, 0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x02,
                0x00, 0x00, 0x00, 0x0A, 0x20, 0x00, 0x00, 0x00, 0x00, 0x03, 0x00, 0x00, 0x00, 0x04);

        assertThat(codec.decode(in)).contains(new MouseMove(1, 2));
        assertThat(codec.decode(in)).contains(new MouseMove(3, 4));
        assertThat(codec.decode(in)).isEmpty();
    }

    @Test
    void rejectsUnknownMessageType() {
        ByteBuffer in = buffer(0x00, 0x00, 0x00, 0x02, 0x7F, 0x00);

        assertThatThrownBy(() -> codec.decode(in))
                .isInstanceOf(ProtocolException.class)
                .hasMessageContaining("0x7f");
    }

    @Test
    void rejectsOversizedFrameBeforeItsBytesArrive() {
        // declared length 16 MiB + 1, no payload bytes yet
        ByteBuffer in = buffer(0x01, 0x00, 0x00, 0x01);

        assertThatThrownBy(() -> codec.decode(in)).isInstanceOf(ProtocolException.class);
    }

    @Test
    void acceptsFrameExactlyAtTheSizeLimitWhileWaitingForBytes() {
        // declared length exactly 16 MiB: legal, just not complete yet
        ByteBuffer in = buffer(0x01, 0x00, 0x00, 0x00);

        assertThat(codec.decode(in)).isEmpty();
    }

    @Test
    void rejectsLengthTooShortToHoldTypeAndFlags() {
        ByteBuffer in = buffer(0x00, 0x00, 0x00, 0x01, 0x20);

        assertThatThrownBy(() -> codec.decode(in)).isInstanceOf(ProtocolException.class);
    }

    @Test
    void rejectsNegativeLength() {
        ByteBuffer in = buffer(0xFF, 0xFF, 0xFF, 0xFF);

        assertThatThrownBy(() -> codec.decode(in)).isInstanceOf(ProtocolException.class);
    }

    @Test
    void rejectsPayloadShorterThanMessageNeeds() {
        // MOUSE_MOVE with only x, y missing
        ByteBuffer in = buffer(0x00, 0x00, 0x00, 0x06, 0x20, 0x00, 0x00, 0x00, 0x00, 0x01);

        assertThatThrownBy(() -> codec.decode(in)).isInstanceOf(ProtocolException.class);
    }

    @Test
    void rejectsTrailingBytesInsidePayload() {
        // MOUSE_MOVE with one extra byte after y
        ByteBuffer in = buffer(
                0x00, 0x00, 0x00, 0x0B, 0x20, 0x00,
                0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x02, 0x00);

        assertThatThrownBy(() -> codec.decode(in)).isInstanceOf(ProtocolException.class);
    }

    @Test
    void customLimitRejectsLongerFrameBeforePayloadArrives() {
        ByteBuffer in = buffer(0x00, 0x00, 0x04, 0x01); // length 1025, payload not sent yet

        assertThatThrownBy(() -> codec.decode(in, 1024)).isInstanceOf(ProtocolException.class);
    }

    @Test
    void customLimitAcceptsFrameAtLimit() {
        int limit = 1024;
        Bye bye = new Bye("x".repeat(limit - 4)); // type + flags + u16 string length + reason = limit
        ByteBuffer in = codec.encode(bye);
        assertThat(in.getInt(0)).isEqualTo(limit);

        assertThat(codec.decode(in, limit)).contains(bye);
    }

    @Test
    void rejectsLimitOutsideRange() {
        ByteBuffer in = buffer(0x00, 0x00, 0x00, 0x02, 0x40, 0x00);

        assertThatThrownBy(() -> codec.decode(in, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> codec.decode(in, MessageCodec.MAX_FRAME_LENGTH + 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private void assertRejected(int... frame) {
        ByteBuffer in = buffer(frame);

        assertThatThrownBy(() -> codec.decode(in)).isInstanceOf(ProtocolException.class);
    }

    @Test
    void tileUpdateWithRectLengthBeyondThePayloadIsRejected() {
        assertRejected(0x00, 0x00, 0x00, 0x24, 0x11, 0x00,
                0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x05, 0x00, 0x40, 0x00, 0x20, 0x01, 0x00, 0x0C, 0x00, 0x01,
                0x00, 0x00, 0x00, 0x00, 0x00, 0x02, 0x00, 0x01, 0x01,
                0xFF, 0xFF, 0xFF, 0xFF, 0x00, 0x11, 0x22, 0x33);
    }

    @Test
    void tileUpdateWithUnknownEncodingIsRejected() {
        assertRejected(0x00, 0x00, 0x00, 0x24, 0x11, 0x00,
                0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x05, 0x00, 0x40, 0x00, 0x20, 0x01, 0x00, 0x0C, 0x00, 0x01,
                0x00, 0x00, 0x00, 0x00, 0x00, 0x02, 0x00, 0x01, 0x05,
                0x00, 0x00, 0x00, 0x04, 0x00, 0x11, 0x22, 0x33);
    }

    @Test
    void tileUpdateWithUnknownFlagBitIsRejected() {
        assertRejected(0x00, 0x00, 0x00, 0x13, 0x11, 0x00,
                0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x06, 0x00, 0x40, 0x00, 0x20, 0x02, 0x00, 0x00, 0x00, 0x00);
    }

    @Test
    void tileUpdateWithRectOutsideTheStreamIsRejected() {
        assertRejected(0x00, 0x00, 0x00, 0x24, 0x11, 0x00,
                0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x05, 0x00, 0x40, 0x00, 0x20, 0x01, 0x00, 0x0C, 0x00, 0x01,
                0x00, 0x3F, 0x00, 0x00, 0x00, 0x02, 0x00, 0x01, 0x01,
                0x00, 0x00, 0x00, 0x04, 0x00, 0x11, 0x22, 0x33);
    }

    @Test
    void tileUpdateWithTrailingBytesIsRejected() {
        assertRejected(0x00, 0x00, 0x00, 0x14, 0x11, 0x00,
                0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x06, 0x00, 0x40, 0x00, 0x20, 0x00, 0x00, 0x00, 0x00, 0x00,
                0x00);
    }

    @Test
    void tileUpdateWithMoreRectsDeclaredThanSentIsRejected() {
        assertRejected(0x00, 0x00, 0x00, 0x24, 0x11, 0x00,
                0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x05, 0x00, 0x40, 0x00, 0x20, 0x01, 0x00, 0x0C, 0x00, 0x02,
                0x00, 0x00, 0x00, 0x00, 0x00, 0x02, 0x00, 0x01, 0x01,
                0x00, 0x00, 0x00, 0x04, 0x00, 0x11, 0x22, 0x33);
    }

    @Test
    void refusesToEncodeTileUpdateAboveSizeLimit() {
        var rect = new TileUpdate.Rect(0, 0, 1, 1, TileUpdate.Encoding.JPEG, new byte[MessageCodec.MAX_FRAME_LENGTH]);
        var update = new TileUpdate(1, 64, 32, true, 0, java.util.List.of(rect));

        assertThatThrownBy(() -> codec.encode(update)).isInstanceOf(IllegalArgumentException.class);
    }

    static int[] bytes(ByteBuffer buffer) {
        ByteBuffer copy = buffer.duplicate();
        int[] out = new int[copy.remaining()];
        for (int i = 0; i < out.length; i++) {
            out[i] = copy.get() & 0xFF;
        }
        return out;
    }

    static ByteBuffer buffer(int... values) {
        ByteBuffer buffer = ByteBuffer.allocate(values.length);
        for (int v : values) {
            buffer.put((byte) v);
        }
        return buffer.flip();
    }
}

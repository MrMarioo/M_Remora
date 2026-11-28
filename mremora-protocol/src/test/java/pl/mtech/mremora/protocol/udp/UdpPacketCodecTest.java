package pl.mtech.mremora.protocol.udp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.stream.Stream;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import pl.mtech.mremora.protocol.udp.UdpPacket.Direction;
import pl.mtech.mremora.protocol.udp.UdpPacket.Type;

class UdpPacketCodecTest {

    private static final int SESSION = 0x01020304;

    private static byte[] key() {
        byte[] key = new byte[32];
        for (int i = 0; i < key.length; i++) {
            key[i] = (byte) i;
        }
        return key;
    }

    private static byte[] hmac16(byte[] key, byte[] data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return Arrays.copyOf(mac.doFinal(data), 16);
    }

    private final UdpPacketCodec codec = new UdpPacketCodec(key());

    private java.util.Optional<UdpPacket> decode(byte[] datagram, int session, Direction from) {
        return codec.decode(ByteBuffer.wrap(datagram), session, from);
    }

    private byte[] validViewerPacket() {
        return codec.encode(new UdpPacket(Type.PROBE, Direction.VIEWER, SESSION, 5, new byte[] {0x0A}));
    }

    /** Re-signs a hand-modified packet so that only the field under test is wrong. */
    private static byte[] resign(byte[] packet) throws Exception {
        byte[] body = Arrays.copyOf(packet, packet.length - 16);
        byte[] mac = hmac16(key(), body);
        byte[] out = Arrays.copyOf(body, packet.length);
        System.arraycopy(mac, 0, out, body.length, 16);
        return out;
    }

    @Test
    void wireFormatOfProbe() throws Exception {
        byte[] wire = validViewerPacket();

        byte[] expectedHead = {0x4D, 0x01, 0x01, 0x01, 0x02, 0x03, 0x04, 0, 0, 0, 0, 0, 0, 0, 0x05, 0x0A};
        assertThat(wire).hasSize(32);
        assertThat(Arrays.copyOf(wire, 16)).containsExactly(expectedHead);
        assertThat(Arrays.copyOfRange(wire, 16, 32)).containsExactly(hmac16(key(), expectedHead));
    }

    @Test
    void tileAndAckTypesAreKnown() {
        assertThat(Type.TILE.code()).isEqualTo(3);
        assertThat(Type.ACK.code()).isEqualTo(4);
        for (Type t : new Type[] {Type.TILE, Type.ACK}) {
            UdpPacket p = new UdpPacket(t, Direction.AGENT, SESSION, 9, new byte[] {1, 2});
            assertThat(decode(codec.encode(p), SESSION, Direction.AGENT)).contains(p);
        }
    }

    static Stream<UdpPacket> roundTripPackets() {
        return Stream.of(Type.values())
                .flatMap(t -> Stream.of(Direction.values())
                        .flatMap(d -> Stream.of(0, 1, 1169)
                                .map(n -> new UdpPacket(t, d, -7, Long.MAX_VALUE - n, filled(n)))));
    }

    private static byte[] filled(int n) {
        byte[] b = new byte[n];
        for (int i = 0; i < n; i++) {
            b[i] = (byte) (i * 31 + 7);
        }
        return b;
    }

    @ParameterizedTest
    @MethodSource("roundTripPackets")
    void roundTrip(UdpPacket packet) {
        byte[] wire = codec.encode(packet);

        assertThat(wire).hasSize(31 + packet.payload().length);
        assertThat(decode(wire, packet.sessionId(), packet.from())).contains(packet);
    }

    @Test
    void encodeRejectsTooLargePayload() {
        assertThatThrownBy(() -> codec.encode(new UdpPacket(Type.PROBE, Direction.AGENT, 1, 1, new byte[1170])))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void tooShort() {
        assertThat(decode(Arrays.copyOf(validViewerPacket(), 30), SESSION, Direction.VIEWER)).isEmpty();
        assertThat(decode(new byte[0], SESSION, Direction.VIEWER)).isEmpty();
    }

    @Test
    void tooLong() {
        UdpPacket p = new UdpPacket(Type.PROBE, Direction.VIEWER, SESSION, 5, new byte[1169]);
        byte[] ok = codec.encode(p);
        assertThat(ok).hasSize(1200);
        assertThat(decode(ok, SESSION, Direction.VIEWER)).isPresent();
        assertThat(decode(Arrays.copyOf(ok, 1201), SESSION, Direction.VIEWER)).isEmpty();
    }

    @Test
    void badMagic() throws Exception {
        byte[] p = validViewerPacket();
        p[0] = 0x4E;
        assertThat(decode(resign(p), SESSION, Direction.VIEWER)).isEmpty();
    }

    @Test
    void reservedFlagBitSet() throws Exception {
        byte[] p = validViewerPacket();
        p[2] |= 0x02;
        assertThat(decode(resign(p), SESSION, Direction.VIEWER)).isEmpty();
    }

    @Test
    void wrongDirectionRejected() {
        byte[] fromAgent = codec.encode(new UdpPacket(Type.PROBE, Direction.AGENT, SESSION, 5, new byte[8]));
        assertThat(decode(fromAgent, SESSION, Direction.VIEWER)).isEmpty();
        assertThat(decode(fromAgent, SESSION, Direction.AGENT)).isPresent();
    }

    @Test
    void wrongSessionId() {
        assertThat(decode(validViewerPacket(), SESSION + 1, Direction.VIEWER)).isEmpty();
    }

    @Test
    void unknownType() throws Exception {
        byte[] p = validViewerPacket();
        p[1] = 5; // 3 = TILE and 4 = ACK are known since plan 2
        assertThat(decode(resign(p), SESSION, Direction.VIEWER)).isEmpty();
    }

    @Test
    void flippedPayloadBit() {
        byte[] p = validViewerPacket();
        p[15] ^= 0x01;
        assertThat(decode(p, SESSION, Direction.VIEWER)).isEmpty();
    }

    @Test
    void flippedMacBit() {
        byte[] p = validViewerPacket();
        p[p.length - 1] ^= 0x01;
        assertThat(decode(p, SESSION, Direction.VIEWER)).isEmpty();
    }

    @Test
    void otherKey() {
        byte[] otherKey = key();
        otherKey[0] ^= 1;
        UdpPacketCodec other = new UdpPacketCodec(otherKey);
        assertThat(other.decode(ByteBuffer.wrap(validViewerPacket()), SESSION, Direction.VIEWER)).isEmpty();
    }

    @Test
    void decodeHonoursPositionAndLimit() {
        byte[] p = validViewerPacket();
        byte[] big = new byte[p.length + 20];
        Arrays.fill(big, (byte) 0x55);
        System.arraycopy(p, 0, big, 7, p.length);

        ByteBuffer buf = ByteBuffer.wrap(big);
        buf.position(7).limit(7 + p.length);

        assertThat(codec.decode(buf, SESSION, Direction.VIEWER)).isPresent();
    }

    @Test
    void keyMustBe32Bytes() {
        assertThatThrownBy(() -> new UdpPacketCodec(new byte[31])).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void negativeNumberRejected() {
        assertThatThrownBy(() -> new UdpPacket(Type.PROBE, Direction.AGENT, 1, -1, new byte[0]))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

package pl.mtech.mremora.protocol.udp;

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import pl.mtech.mremora.protocol.udp.UdpPacket.Direction;
import pl.mtech.mremora.protocol.udp.UdpPacket.Type;

/**
 * Encodes and verifies signed UDP datagrams (big-endian):
 * {@code u8 magic=0x4D | u8 type | u8 flags | u32 sessionId | u64 number | payload | 16 B MAC}.
 * {@code flags} bit 0 is the sender direction (0 agent, 1 viewer); other bits must be zero.
 * {@code MAC = HMAC-SHA256(key, header || payload)[0..16)}. Replay protection is the caller's job.
 * Not thread-safe (the {@link Mac} is reused).
 */
public final class UdpPacketCodec {

    public static final int MAX_DATAGRAM = 1200;
    public static final int HEADER = 15;
    public static final int MAC_LENGTH = 16;
    public static final int MAX_PAYLOAD = MAX_DATAGRAM - HEADER - MAC_LENGTH;
    public static final int MAGIC = 0x4D;

    private static final int KEY_LENGTH = 32;
    private static final int FLAG_VIEWER = 0x01;

    private final Mac mac;

    public UdpPacketCodec(byte[] key) {
        if (key.length != KEY_LENGTH) {
            throw new IllegalArgumentException("key must be " + KEY_LENGTH + " bytes: " + key.length);
        }
        try {
            mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.clone(), "HmacSHA256"));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }

    public byte[] encode(UdpPacket p) {
        byte[] payload = p.payload();
        if (payload.length > MAX_PAYLOAD) {
            throw new IllegalArgumentException("payload too large: " + payload.length);
        }
        int bodyLength = HEADER + payload.length;
        ByteBuffer out = ByteBuffer.allocate(bodyLength + MAC_LENGTH);
        out.put((byte) MAGIC)
                .put((byte) p.type().code())
                .put((byte) (p.from() == Direction.VIEWER ? FLAG_VIEWER : 0))
                .putInt(p.sessionId())
                .putLong(p.number())
                .put(payload);
        byte[] wire = out.array();
        mac.update(wire, 0, bodyLength);
        byte[] tag = mac.doFinal();
        System.arraycopy(tag, 0, wire, bodyLength, MAC_LENGTH);
        return wire;
    }

    /** Never throws for malformed input; returns empty for anything that is not a valid packet. */
    public Optional<UdpPacket> decode(ByteBuffer datagram, int expectedSessionId, Direction expectedFrom) {
        int length = datagram.remaining();
        if (length < HEADER + MAC_LENGTH || length > MAX_DATAGRAM) {
            return Optional.empty();
        }
        byte[] wire = new byte[length];
        datagram.get(datagram.position(), wire);

        if ((wire[0] & 0xFF) != MAGIC) {
            return Optional.empty();
        }
        int flags = wire[2] & 0xFF;
        if ((flags & ~FLAG_VIEWER) != 0) {
            return Optional.empty();
        }
        Direction from = (flags & FLAG_VIEWER) != 0 ? Direction.VIEWER : Direction.AGENT;
        if (from != expectedFrom) {
            return Optional.empty();
        }
        ByteBuffer in = ByteBuffer.wrap(wire);
        if (in.getInt(3) != expectedSessionId) {
            return Optional.empty();
        }
        Type type = Type.fromCode(wire[1] & 0xFF);
        if (type == null) {
            return Optional.empty();
        }
        int bodyLength = length - MAC_LENGTH;
        mac.update(wire, 0, bodyLength);
        byte[] expected = Arrays.copyOf(mac.doFinal(), MAC_LENGTH);
        byte[] actual = Arrays.copyOfRange(wire, bodyLength, length);
        if (!MessageDigest.isEqual(expected, actual)) {
            return Optional.empty();
        }
        long number = in.getLong(7);
        if (number < 0) {
            return Optional.empty();
        }
        return Optional.of(new UdpPacket(type, from, expectedSessionId, number, Arrays.copyOfRange(wire, HEADER, bodyLength)));
    }
}

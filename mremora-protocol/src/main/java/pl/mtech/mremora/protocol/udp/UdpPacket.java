package pl.mtech.mremora.protocol.udp;

import java.util.Arrays;
import java.util.Objects;

/**
 * One UDP datagram. {@code from} is the sender and is covered by the signature, so a packet reflected back to its
 * sender is rejected. {@code payload} is not copied (hot path) - callers must not modify it afterwards.
 */
public record UdpPacket(Type type, Direction from, int sessionId, long number, byte[] payload) {

    public enum Type {
        PROBE(1),
        PROBE_REPLY(2),
        /** One fragment of an image rectangle ({@link TileFragment}); needs an ACK. */
        TILE(3),
        /** Acknowledgement ({@link AckFrame}); itself never acknowledged. */
        ACK(4);

        private final int code;

        Type(int code) {
            this.code = code;
        }

        public int code() {
            return code;
        }

        static Type fromCode(int code) {
            for (Type t : values()) {
                if (t.code == code) {
                    return t;
                }
            }
            return null;
        }
    }

    /** The sender of a packet. */
    public enum Direction {
        AGENT,
        VIEWER
    }

    public UdpPacket {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(payload, "payload");
        if (number < 0) {
            throw new IllegalArgumentException("number must be >= 0: " + number);
        }
        if (payload.length > UdpPacketCodec.MAX_PAYLOAD) {
            throw new IllegalArgumentException("payload too large: " + payload.length);
        }
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof UdpPacket(Type t, Direction f, int s, long n, byte[] p)
                && t == type && f == from && s == sessionId && n == number && Arrays.equals(p, payload);
    }

    @Override
    public int hashCode() {
        return Objects.hash(type, from, sessionId, number, Arrays.hashCode(payload));
    }

    @Override
    public String toString() {
        return "UdpPacket[type=%s, from=%s, sessionId=%d, number=%d, %d bytes]"
                .formatted(type, from, sessionId, number, payload.length);
    }
}

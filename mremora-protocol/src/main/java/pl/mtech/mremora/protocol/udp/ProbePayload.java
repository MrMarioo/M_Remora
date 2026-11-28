package pl.mtech.mremora.protocol.udp;

import java.nio.ByteBuffer;
import java.util.OptionalLong;

/** Payload of PROBE / PROBE_REPLY: {@code u64 sentNanos} (sender's clock) followed by zero padding. */
public final class ProbePayload {

    private static final int MIN_LENGTH = 8;

    private ProbePayload() {}

    public static byte[] encode(long sentNanos, int totalLength) {
        if (totalLength < MIN_LENGTH || totalLength > UdpPacketCodec.MAX_PAYLOAD) {
            throw new IllegalArgumentException(
                    "totalLength must be in [%d, %d]: %d".formatted(MIN_LENGTH, UdpPacketCodec.MAX_PAYLOAD, totalLength));
        }
        byte[] out = new byte[totalLength];
        ByteBuffer.wrap(out).putLong(sentNanos);
        return out;
    }

    public static OptionalLong sentNanos(byte[] payload) {
        if (payload.length < MIN_LENGTH) {
            return OptionalLong.empty();
        }
        return OptionalLong.of(ByteBuffer.wrap(payload).getLong());
    }
}

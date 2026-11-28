package pl.mtech.mremora.protocol;

import java.util.Arrays;
import java.util.Objects;

/**
 * Agent to viewer (protocol v3): parameters of the signed UDP channel. {@code key} is a 32-byte
 * secret; it is copied on the way in and out, compared by content, and never printed.
 */
public record UdpSetup(int udpPort, int sessionId, byte[] key) implements Message {

    public static final int KEY_LENGTH = 32;

    public UdpSetup {
        Objects.requireNonNull(key, "key");
        if (udpPort < 1 || udpPort > 0xFFFF) {
            throw new IllegalArgumentException("UDP port out of range 1..65535: " + udpPort);
        }
        if (key.length != KEY_LENGTH) {
            throw new IllegalArgumentException("Key must be %d bytes, was %d".formatted(KEY_LENGTH, key.length));
        }
        key = key.clone();
    }

    @Override
    public byte[] key() {
        return key.clone();
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof UdpSetup other
                && udpPort == other.udpPort && sessionId == other.sessionId && Arrays.equals(key, other.key);
    }

    @Override
    public int hashCode() {
        return Objects.hash(udpPort, sessionId) * 31 + Arrays.hashCode(key);
    }

    @Override
    public String toString() {
        return "UdpSetup[udpPort=%d, sessionId=%d, key=<%d bytes>]".formatted(udpPort, sessionId, key.length);
    }
}

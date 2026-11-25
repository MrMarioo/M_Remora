package pl.mtech.mremora.protocol;

import java.util.Objects;

/**
 * First message on every connection. Carries the protocol version so both sides can
 * negotiate, and the pairing code (empty when the sender does not need one).
 */
public record Hello(int protocolVersion, Role role, String pairingCode) implements Message {

    /** Current wire protocol version. */
    public static final int PROTOCOL_VERSION = 1;

    public enum Role { AGENT, VIEWER }

    public Hello {
        if (protocolVersion < 1 || protocolVersion > 0xFFFF) {
            throw new IllegalArgumentException("protocolVersion must be 1..65535, was " + protocolVersion);
        }
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(pairingCode, "pairingCode");
    }
}

package pl.mtech.mremora.viewer;

import java.io.IOException;
import java.util.Objects;

/** The agent refused the connection with a {@code Bye} during the handshake. */
public final class AgentRejectedException extends IOException {

    private final String reason;

    public AgentRejectedException(String reason) {
        super("Agent rejected the connection: " + reason);
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    /** The reason from the agent's {@code Bye}, e.g. {@code invalid pairing code}. */
    public String reason() {
        return reason;
    }
}

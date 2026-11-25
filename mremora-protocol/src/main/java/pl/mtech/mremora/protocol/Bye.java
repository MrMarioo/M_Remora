package pl.mtech.mremora.protocol;

import java.util.Objects;

/**
 * Graceful end of the session, with a human-readable reason.
 */
public record Bye(String reason) implements Message {

    public Bye {
        Objects.requireNonNull(reason, "reason");
    }
}

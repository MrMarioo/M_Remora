package pl.mtech.mremora.protocol;

import java.util.Objects;

/**
 * Agent to viewer (protocol v3): which transport carries the picture from now on.
 * {@code epoch} (0..65535) numbers the decisions so a late message can be told from a current one.
 */
public record TransportState(Mode mode, int epoch) implements Message {

    public enum Mode { TCP, UDP }

    public TransportState {
        Objects.requireNonNull(mode, "mode");
        if (epoch < 0 || epoch > 0xFFFF) {
            throw new IllegalArgumentException("Epoch out of u16 range: " + epoch);
        }
    }
}

package pl.mtech.mremora.protocol;

import java.util.Objects;

public record MouseButton(Button button, boolean pressed) implements Message {

    public enum Button { LEFT, MIDDLE, RIGHT }

    public MouseButton {
        Objects.requireNonNull(button, "button");
    }
}

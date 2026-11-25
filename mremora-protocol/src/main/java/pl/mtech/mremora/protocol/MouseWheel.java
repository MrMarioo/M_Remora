package pl.mtech.mremora.protocol;

/**
 * Wheel rotation in notches; positive scrolls down (towards the user), like {@code java.awt.Robot#mouseWheel}.
 */
public record MouseWheel(int notches) implements Message {
}

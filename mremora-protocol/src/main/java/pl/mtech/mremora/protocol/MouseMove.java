package pl.mtech.mremora.protocol;

/**
 * Absolute pointer position in the agent's physical screen pixels.
 * Coordinates may be negative on multi-monitor layouts.
 */
public record MouseMove(int x, int y) implements Message {
}

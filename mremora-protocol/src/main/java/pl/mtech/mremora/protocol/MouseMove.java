package pl.mtech.mremora.protocol;

/**
 * Absolute pointer position in the agent's physical screen pixels.
 * Physical pixels relative to the top-left corner of the captured screen; the agent clamps
 * out-of-range values to the screen edge.
 */
public record MouseMove(int x, int y) implements Message {
}

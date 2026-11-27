package pl.mtech.mremora.protocol;

/**
 * Viewer to agent: the frame or tile batch with this {@code sequence} has been fully applied.
 * The sequence is an unsigned 64-bit value on the wire; any {@code long} is accepted.
 */
public record FrameAck(long sequence) implements Message {
}

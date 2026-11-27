package pl.mtech.mremora.protocol;

/**
 * Agent to viewer: where the remote cursor is, in the agent's physical pixels.
 * Any {@code int} is valid (the cursor may sit on a monitor left of or above the origin).
 */
public record CursorPosition(int x, int y) implements Message {
}

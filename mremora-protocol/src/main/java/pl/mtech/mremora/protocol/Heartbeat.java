package pl.mtech.mremora.protocol;

/**
 * Keeps an idle connection alive and lets the other side detect a dead one.
 */
public record Heartbeat() implements Message {
}

package pl.mtech.mremora.protocol;

/**
 * A single protocol message. Every message travels in its own frame.
 */
public sealed interface Message
        permits Hello, ScreenInfo, FrameUpdate, MouseMove, MouseButton, MouseWheel, Heartbeat, Bye {
}

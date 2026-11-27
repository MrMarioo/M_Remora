package pl.mtech.mremora.protocol.netty;

import io.netty.channel.ChannelPipeline;
import io.netty.handler.timeout.IdleStateHandler;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/** Installs the MRemora wire handlers; the application handler is added after them. */
public final class MessagePipeline {

    private MessagePipeline() {
    }

    /**
     * Adds idle detection, decoder, encoder and heartbeat handling, in that order.
     * Idle detection comes first so it counts raw bytes in both directions, not whole decoded messages:
     * a large frame still arriving byte by byte keeps the link alive.
     *
     * @param maxInboundFrame   largest accepted inbound frame length
     * @param heartbeatInterval silence on the write side after which a heartbeat is sent
     * @param idleTimeout       silence on the read side after which the link is closed
     */
    public static void install(ChannelPipeline p, int maxInboundFrame,
                               Duration heartbeatInterval, Duration idleTimeout) {
        p.addLast(new IdleStateHandler(idleTimeout.toMillis(), heartbeatInterval.toMillis(), 0, TimeUnit.MILLISECONDS));
        p.addLast(new MessageDecoder(maxInboundFrame));
        p.addLast(new MessageEncoder());
        p.addLast(new HeartbeatHandler());
    }
}

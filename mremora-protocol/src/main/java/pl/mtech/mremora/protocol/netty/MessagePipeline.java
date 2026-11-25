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
     * Adds decoder, encoder, idle detection and heartbeat handling, in that order.
     *
     * @param maxInboundFrame   largest accepted inbound frame length
     * @param heartbeatInterval silence on the write side after which a heartbeat is sent
     * @param idleTimeout       silence on the read side after which the link is closed
     */
    public static void install(ChannelPipeline p, int maxInboundFrame,
                               Duration heartbeatInterval, Duration idleTimeout) {
        p.addLast(new MessageDecoder(maxInboundFrame));
        p.addLast(new MessageEncoder());
        p.addLast(new IdleStateHandler(idleTimeout.toMillis(), heartbeatInterval.toMillis(), 0, TimeUnit.MILLISECONDS));
        p.addLast(new HeartbeatHandler());
    }
}

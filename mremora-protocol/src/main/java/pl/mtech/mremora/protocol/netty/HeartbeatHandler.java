package pl.mtech.mremora.protocol.netty;

import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import pl.mtech.mremora.protocol.Bye;
import pl.mtech.mremora.protocol.Heartbeat;

/**
 * Keeps the link alive: sends a {@link Heartbeat} on writer idle, and on reader idle says {@code Bye("timeout")}
 * if it can be flushed right away and closes. Incoming heartbeats are swallowed.
 * Needs an {@code IdleStateHandler} before it.
 */
public final class HeartbeatHandler extends ChannelDuplexHandler {

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (msg instanceof Heartbeat) {
            return;
        }
        super.channelRead(ctx, msg);
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        if (evt instanceof IdleStateEvent idle) {
            if (idle.state() == IdleState.WRITER_IDLE) {
                ctx.writeAndFlush(new Heartbeat());
            } else if (idle.state() == IdleState.READER_IDLE) {
                // Close even when the Bye cannot be flushed (a peer that stopped reading holds the write forever).
                ctx.writeAndFlush(new Bye("timeout"));
                ctx.close();
            }
            return;
        }
        super.userEventTriggered(ctx, evt);
    }
}

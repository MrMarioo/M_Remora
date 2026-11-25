package pl.mtech.mremora.protocol.netty;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageEncoder;
import java.util.List;
import pl.mtech.mremora.protocol.Message;
import pl.mtech.mremora.protocol.MessageCodec;

/** Encodes outbound {@link Message}s into framed bytes using {@link MessageCodec}. */
public final class MessageEncoder extends MessageToMessageEncoder<Message> {

    private final MessageCodec codec = new MessageCodec();

    @Override
    protected void encode(ChannelHandlerContext ctx, Message msg, List<Object> out) {
        out.add(Unpooled.wrappedBuffer(codec.encode(msg)));
    }
}

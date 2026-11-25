package pl.mtech.mremora.protocol.netty;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Optional;
import pl.mtech.mremora.protocol.Message;
import pl.mtech.mremora.protocol.MessageCodec;

/**
 * Turns the inbound byte stream into {@link Message}s using {@link MessageCodec}.
 * A {@link pl.mtech.mremora.protocol.ProtocolException} propagates (Netty wraps it in a {@code DecoderException}).
 */
public final class MessageDecoder extends ByteToMessageDecoder {

    private final MessageCodec codec = new MessageCodec();
    private final int maxFrameLength;

    public MessageDecoder(int maxFrameLength) {
        this.maxFrameLength = maxFrameLength;
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        while (in.isReadable()) {
            ByteBuffer view = in.nioBuffer(in.readerIndex(), in.readableBytes());
            Optional<Message> message = codec.decode(view, maxFrameLength);
            if (message.isEmpty()) {
                return;
            }
            in.skipBytes(view.position());
            out.add(message.get());
        }
    }
}

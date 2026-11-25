package pl.mtech.mremora.protocol.netty;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.timeout.IdleStateEvent;
import java.nio.ByteBuffer;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import pl.mtech.mremora.protocol.Bye;
import pl.mtech.mremora.protocol.Heartbeat;
import pl.mtech.mremora.protocol.Message;
import pl.mtech.mremora.protocol.MessageCodec;
import pl.mtech.mremora.protocol.MouseMove;
import pl.mtech.mremora.protocol.MouseWheel;
import pl.mtech.mremora.protocol.ProtocolException;

class MessagePipelineTest {

    private final MessageCodec codec = new MessageCodec();
    private EmbeddedChannel channel;

    @BeforeEach
    void setUp() {
        channel = new EmbeddedChannel();
        MessagePipeline.install(channel.pipeline(), 1024, Duration.ofSeconds(2), Duration.ofSeconds(6));
    }

    @AfterEach
    void tearDown() {
        channel.finishAndReleaseAll();
    }

    private byte[] bytes(Message m) {
        ByteBuffer b = codec.encode(m);
        byte[] out = new byte[b.remaining()];
        b.get(out);
        return out;
    }

    @Test
    void decodesFrameSplitIntoChunks() {
        for (byte b : bytes(new MouseMove(1, 2))) {
            channel.writeInbound(Unpooled.wrappedBuffer(new byte[] {b}));
        }
        assertThat((Object) channel.readInbound()).isEqualTo(new MouseMove(1, 2));
        assertThat((Object) channel.readInbound()).isNull();
    }

    @Test
    void decodesSeveralFramesInOneBuffer() {
        byte[] a = bytes(new MouseMove(1, 2));
        byte[] b = bytes(new MouseWheel(3));
        ByteBuf buf = Unpooled.buffer().writeBytes(a).writeBytes(b);
        channel.writeInbound(buf);
        assertThat((Object) channel.readInbound()).isEqualTo(new MouseMove(1, 2));
        assertThat((Object) channel.readInbound()).isEqualTo(new MouseWheel(3));
        assertThat((Object) channel.readInbound()).isNull();
    }

    @Test
    void oversizedFrameRaisesException() {
        assertThatThrownBy(() -> channel.writeInbound(Unpooled.buffer().writeInt(2000)))
                .isInstanceOf(DecoderException.class)
                .hasCauseInstanceOf(ProtocolException.class);
        // closing re-runs the decoder over the unconsumed bytes and records the same failure again
        channel.close();
        assertThatThrownBy(channel::checkException).isInstanceOf(DecoderException.class);
    }

    @Test
    void encodesMessageToSameBytesAsCodec() {
        channel.writeOutbound(new Bye("x"));
        ByteBuf out = channel.readOutbound();
        byte[] actual = new byte[out.readableBytes()];
        out.readBytes(actual);
        out.release();
        assertThat(actual).isEqualTo(bytes(new Bye("x")));
    }

    @Test
    void heartbeatIsSwallowed() {
        channel.writeInbound(Unpooled.wrappedBuffer(bytes(new Heartbeat())));
        assertThat((Object) channel.readInbound()).isNull();
    }

    @Test
    void writerIdleSendsHeartbeat() {
        channel.pipeline().fireUserEventTriggered(IdleStateEvent.WRITER_IDLE_STATE_EVENT);
        assertThat(readOutboundMessage()).isEqualTo(new Heartbeat());
        assertThat(channel.isOpen()).isTrue();
    }

    @Test
    void readerIdleSendsByeTimeoutAndCloses() {
        channel.pipeline().fireUserEventTriggered(IdleStateEvent.READER_IDLE_STATE_EVENT);
        assertThat(readOutboundMessage()).isEqualTo(new Bye("timeout"));
        assertThat(channel.isOpen()).isFalse();
    }

    private Message readOutboundMessage() {
        ByteBuf out = channel.readOutbound();
        ByteBuffer nio = out.nioBuffer();
        out.release();
        return codec.decode(nio).orElseThrow();
    }
}

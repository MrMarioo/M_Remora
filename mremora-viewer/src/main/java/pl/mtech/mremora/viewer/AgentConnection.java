package pl.mtech.mremora.viewer;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.util.concurrent.ScheduledFuture;
import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import pl.mtech.mremora.protocol.Bye;
import pl.mtech.mremora.protocol.FrameUpdate;
import pl.mtech.mremora.protocol.Hello;
import pl.mtech.mremora.protocol.Message;
import pl.mtech.mremora.protocol.MessageCodec;
import pl.mtech.mremora.protocol.MouseButton;
import pl.mtech.mremora.protocol.MouseMove;
import pl.mtech.mremora.protocol.MouseWheel;
import pl.mtech.mremora.protocol.ScreenInfo;
import pl.mtech.mremora.protocol.netty.HeartbeatHandler;
import pl.mtech.mremora.protocol.netty.MessagePipeline;

/**
 * Viewer side of one session with an agent: handshake with the pairing code, then frames in and mouse input out.
 *
 * <p>Each connection runs on its own single-threaded Netty event loop, which is shut down when the connection ends.
 */
public final class AgentConnection implements AutoCloseable {

    static final String CLOSED_BY_VIEWER = "closed by viewer";
    static final String CONNECTION_LOST = "connection lost";
    static final String PROTOCOL_ERROR = "protocol error";

    static final Duration HANDSHAKE_TIMEOUT = Duration.ofSeconds(10);
    static final Duration HEARTBEAT_INTERVAL = Duration.ofSeconds(2);
    static final Duration IDLE_TIMEOUT = Duration.ofSeconds(6);

    private static final Logger LOG = System.getLogger(AgentConnection.class.getName());
    private static final Duration SHUTDOWN_WAIT = Duration.ofSeconds(5);

    /** Events of an established connection, called on the connection's Netty thread; must not block. */
    public interface Listener {

        /** A new screen frame arrived. */
        void onFrame(FrameUpdate frame);

        /**
         * The connection ended; called exactly once after a successful {@link #connect}.
         *
         * @param reason the reason from the {@code Bye} that ended it (sent by either side),
         *     or {@code connection lost}
         */
        void onClosed(String reason);
    }

    private final Channel channel;
    private final EventLoopGroup group;
    private final Handler handler;
    private final ScreenInfo screenInfo;

    private AgentConnection(Channel channel, EventLoopGroup group, Handler handler, ScreenInfo screenInfo) {
        this.channel = channel;
        this.group = group;
        this.handler = handler;
        this.screenInfo = screenInfo;
    }

    /**
     * Connects to an agent and authenticates with its pairing code; whitespace in the code is ignored.
     *
     * @return completes after the agent's {@code Hello} and {@link ScreenInfo}; fails with
     *     {@link AgentRejectedException} when the agent says {@code Bye} first, with {@link TimeoutException}
     *     when the agent does not complete it within 10 s (or stays completely silent for 6 s, the idle timeout),
     *     or with the {@link IOException} of the failed connection
     */
    public static CompletableFuture<AgentConnection> connect(String host, int port, String pairingCode,
                                                             Listener listener) {
        Objects.requireNonNull(host, "host");
        Objects.requireNonNull(pairingCode, "pairingCode");
        Objects.requireNonNull(listener, "listener");
        String code = pairingCode.replaceAll("\\s", "");
        CompletableFuture<AgentConnection> result = new CompletableFuture<>();
        EventLoopGroup group = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
        Handler handler = new Handler(code, listener, result, group);
        try {
            ChannelFuture connected = new Bootstrap()
                    .group(group)
                    .channel(NioSocketChannel.class)
                    .option(ChannelOption.TCP_NODELAY, true)
                    .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) HANDSHAKE_TIMEOUT.toMillis())
                    .handler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            MessagePipeline.install(ch.pipeline(), MessageCodec.MAX_FRAME_LENGTH,
                                    HEARTBEAT_INTERVAL, IDLE_TIMEOUT);
                            ch.pipeline().addBefore(ch.pipeline().context(HeartbeatHandler.class).name(),
                                    "handshake-idle", new HandshakeIdleHandler(handler));
                            ch.pipeline().addLast("connection", handler);
                        }
                    })
                    .connect(host, port);
            connected.channel().closeFuture().addListener(f -> shutdown(group));
            connected.addListener(f -> {
                if (!f.isSuccess()) {
                    result.completeExceptionally(f.cause());
                }
            });
        } catch (RuntimeException e) { // e.g. a port out of range
            shutdown(group);
            throw e;
        }
        return result;
    }

    /** Geometry of the agent's screen, in physical pixels. */
    public ScreenInfo screenInfo() {
        return screenInfo;
    }

    /**
     * Sends mouse input to the agent; ignored once the connection is closed.
     *
     * @param input a {@link MouseMove}, {@link MouseButton} or {@link MouseWheel}
     * @throws IllegalArgumentException for any other message
     */
    public void sendInput(Message input) {
        if (!(input instanceof MouseMove || input instanceof MouseButton || input instanceof MouseWheel)) {
            throw new IllegalArgumentException("Not an input message: " + input);
        }
        channel.writeAndFlush(input);
    }

    /**
     * Says {@code Bye("closed by viewer")}, closes the connection and stops its thread. Idempotent.
     * When called from a {@link Listener} callback it does not wait for the shutdown.
     */
    @Override
    public void close() {
        try {
            channel.eventLoop().execute(() -> handler.sayByeAndClose(channel, CLOSED_BY_VIEWER));
        } catch (RejectedExecutionException e) {
            // The connection already ended and its thread is gone.
        }
        if (channel.eventLoop().inEventLoop()) {
            return;
        }
        channel.closeFuture().awaitUninterruptibly(SHUTDOWN_WAIT.toMillis());
        shutdown(group).awaitUninterruptibly(SHUTDOWN_WAIT.toMillis());
    }

    private static io.netty.util.concurrent.Future<?> shutdown(EventLoopGroup group) {
        return group.shutdownGracefully(0, SHUTDOWN_WAIT.toMillis(), TimeUnit.MILLISECONDS);
    }

    /** Handshake, then the established session. All state lives on the connection's event loop. */
    private static final class Handler extends ChannelInboundHandlerAdapter {

        private final String code;
        private final Listener listener;
        private final CompletableFuture<AgentConnection> result;
        private final EventLoopGroup group;

        private ScheduledFuture<?> handshakeTimeout;
        private boolean helloReceived;
        /** Set once the future completed with a connection; from then on {@code onClosed} is owed. */
        private boolean established;
        private boolean closedReported;
        /** Reason of the {@code Bye} that ends the connection, from either side. */
        private String byeReason;

        Handler(String code, Listener listener, CompletableFuture<AgentConnection> result, EventLoopGroup group) {
            this.code = code;
            this.listener = listener;
            this.result = result;
            this.group = group;
        }

        @Override
        public void channelActive(ChannelHandlerContext ctx) throws Exception {
            ctx.writeAndFlush(new Hello(Hello.PROTOCOL_VERSION, Hello.Role.VIEWER, code));
            handshakeTimeout = ctx.executor().schedule(() -> failHandshake(ctx,
                            new TimeoutException("No answer from the agent within " + HANDSHAKE_TIMEOUT.toSeconds() + " s")),
                    HANDSHAKE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            super.channelActive(ctx);
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            if (!ctx.channel().isActive()) {
                return;
            }
            if (established) {
                onSessionMessage(ctx, (Message) msg);
            } else {
                onHandshakeMessage(ctx, (Message) msg);
            }
        }

        private void onHandshakeMessage(ChannelHandlerContext ctx, Message msg) {
            switch (msg) {
                case Bye bye -> failHandshake(ctx, new AgentRejectedException(bye.reason()));
                case Hello hello when !helloReceived && hello.role() == Hello.Role.AGENT
                        && hello.protocolVersion() <= Hello.PROTOCOL_VERSION -> helloReceived = true;
                case ScreenInfo info when helloReceived -> {
                    handshakeTimeout.cancel(false);
                    AgentConnection connection = new AgentConnection(ctx.channel(), group, this, info);
                    established = true;
                    if (!result.complete(connection)) { // the caller gave up waiting (e.g. cancelled)
                        established = false;
                        sayByeAndClose(ctx.channel(), CLOSED_BY_VIEWER);
                    }
                }
                default -> {
                    ctx.writeAndFlush(new Bye(PROTOCOL_ERROR));
                    failHandshake(ctx, new IOException("Unexpected message during handshake: " + msg));
                }
            }
        }

        private void onSessionMessage(ChannelHandlerContext ctx, Message msg) {
            switch (msg) {
                case FrameUpdate frame -> {
                    try {
                        listener.onFrame(frame);
                    } catch (RuntimeException e) {
                        LOG.log(Level.ERROR, "Frame listener failed", e);
                    }
                }
                case Bye bye -> {
                    if (byeReason == null) {
                        byeReason = bye.reason();
                    }
                    ctx.close();
                }
                default -> LOG.log(Level.DEBUG, "Ignoring unexpected message: {0}", msg);
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            if (!ctx.channel().isActive()) {
                return; // the decoder rethrows its leftover bytes on close
            }
            if (!established) {
                failHandshake(ctx, cause instanceof IOException io ? io : new IOException(cause));
                return;
            }
            if (cause instanceof IOException) {
                LOG.log(Level.DEBUG, "Connection failed: {0}", cause.toString());
                ctx.close();
                return;
            }
            LOG.log(Level.DEBUG, "Protocol error: {0}", cause.toString());
            sayByeAndClose(ctx.channel(), PROTOCOL_ERROR);
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) throws Exception {
            if (handshakeTimeout != null) {
                handshakeTimeout.cancel(false);
            }
            result.completeExceptionally(new IOException("Connection closed by the agent during the handshake"));
            if (established && !closedReported) {
                closedReported = true;
                try {
                    listener.onClosed(byeReason != null ? byeReason : CONNECTION_LOST);
                } catch (RuntimeException e) {
                    LOG.log(Level.ERROR, "Close listener failed", e);
                }
            }
            super.channelInactive(ctx);
        }

        /** Event loop: ends the connection with {@code Bye(reason)}; a no-op once it is closed. */
        void sayByeAndClose(Channel ch, String reason) {
            if (!ch.isActive()) {
                return;
            }
            if (byeReason == null) {
                byeReason = reason;
            }
            // Close even when the Bye cannot be flushed: an agent that stopped reading would hold it forever.
            ch.writeAndFlush(new Bye(reason));
            ch.close();
        }

        /**
         * Event loop: the agent stayed silent for the idle timeout; during the handshake that is a timeout.
         * The {@link HeartbeatHandler} then says {@code Bye("timeout")} and closes.
         */
        void readerIdle() {
            result.completeExceptionally(
                    new TimeoutException("No answer from the agent within " + IDLE_TIMEOUT.toSeconds() + " s"));
        }

        private void failHandshake(ChannelHandlerContext ctx, Throwable cause) {
            if (result.completeExceptionally(cause)) {
                ctx.close();
            }
        }
    }

    /**
     * Sits before the {@link HeartbeatHandler}, which closes a silent link with {@code Bye("timeout")}:
     * reports that close as a handshake timeout instead of a lost connection.
     */
    private static final class HandshakeIdleHandler extends ChannelInboundHandlerAdapter {

        private final Handler handler;

        HandshakeIdleHandler(Handler handler) {
            this.handler = handler;
        }

        @Override
        public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
            if (evt instanceof IdleStateEvent idle && idle.state() == IdleState.READER_IDLE) {
                handler.readerIdle();
            }
            super.userEventTriggered(ctx, evt);
        }
    }
}

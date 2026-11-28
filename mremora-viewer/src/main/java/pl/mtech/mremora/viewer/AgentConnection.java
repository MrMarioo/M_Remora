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
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import pl.mtech.mremora.protocol.Bye;
import pl.mtech.mremora.protocol.DevProtocolVersion;
import pl.mtech.mremora.protocol.FrameAck;
import pl.mtech.mremora.protocol.FrameUpdate;
import pl.mtech.mremora.protocol.Hello;
import pl.mtech.mremora.protocol.Message;
import pl.mtech.mremora.protocol.MessageCodec;
import pl.mtech.mremora.protocol.MouseButton;
import pl.mtech.mremora.protocol.MouseMove;
import pl.mtech.mremora.protocol.MouseWheel;
import pl.mtech.mremora.protocol.ScreenInfo;
import pl.mtech.mremora.protocol.StreamConfig;
import pl.mtech.mremora.protocol.TileUpdate;
import pl.mtech.mremora.protocol.TransportState;
import pl.mtech.mremora.protocol.UdpSetup;
import pl.mtech.mremora.protocol.netty.HeartbeatHandler;
import pl.mtech.mremora.protocol.netty.LinkImpairment;
import pl.mtech.mremora.protocol.netty.MessagePipeline;
import pl.mtech.mremora.protocol.udp.CompleteRect;

/**
 * Viewer side of one session with an agent: handshake with the pairing code, then frames in and mouse input out.
 * On protocol v2 frames come as tiles and the viewer also sends {@code FrameAck} and {@code StreamConfig}.
 * On protocol v3 the agent also offers a signed UDP channel ({@code UdpSetup}), which the viewer opens and probes
 * ({@link ViewerUdpLink}), and announces the transport of the picture ({@code TransportState}). Rectangles of the
 * picture over UDP go to {@link Listener#onUdpRect}; the caller reports how each ended
 * ({@link #udpRectApplied}, {@link #udpRectDropped}).
 *
 * <p>Each connection runs on its own single-threaded Netty event loop, which is shut down when the connection ends.
 */
public final class AgentConnection implements AutoCloseable {

    static final String CLOSED_BY_VIEWER = "closed by viewer";
    static final String CONNECTION_LOST = "connection lost";
    static final String PROTOCOL_ERROR = "protocol error";

    /**
     * Highest protocol version this viewer can speak (v2: tiles, v3: plus the UDP channel), whatever
     * {@link Hello#PROTOCOL_VERSION} says.
     */
    static final int HIGHEST_PROTOCOL_VERSION = 3;

    /**
     * Version proposed when none is given: {@link Hello#PROTOCOL_VERSION}, capped at what this viewer implements,
     * so bumping the wire constant alone never makes {@link #connect} throw.
     */
    static final int DEFAULT_PROTOCOL_VERSION = Math.min(Hello.PROTOCOL_VERSION, HIGHEST_PROTOCOL_VERSION);

    /**
     * Version proposed by the connects that take none: {@link #DEFAULT_PROTOCOL_VERSION}, unless the developer
     * property {@value DevProtocolVersion#PROPERTY} selects another (logged as a {@code WARNING}).
     *
     * @throws IllegalArgumentException for an invalid property value
     */
    static int defaultProtocolVersion() {
        return DevProtocolVersion.fromSystemProperty(DEFAULT_PROTOCOL_VERSION, HIGHEST_PROTOCOL_VERSION);
    }

    /**
     * At startup: checks the developer properties read by the connects ({@value DevProtocolVersion#PROPERTY},
     * {@value LinkImpairment#DEV_PROPERTY}), so a typo stops the viewer at once instead of at the first connect.
     *
     * @throws IllegalArgumentException naming the property and the bad value
     */
    static void checkDevProperties() {
        defaultProtocolVersion();
        LinkImpairment.fromSystemProperty();
    }

    static final Duration HANDSHAKE_TIMEOUT = Duration.ofSeconds(10);
    static final Duration HEARTBEAT_INTERVAL = Duration.ofSeconds(2);
    static final Duration IDLE_TIMEOUT = Duration.ofSeconds(6);

    private static final Logger LOG = System.getLogger(AgentConnection.class.getName());
    private static final Duration SHUTDOWN_WAIT = Duration.ofSeconds(5);

    /** Events of an established connection, called on the connection's Netty thread; must not block. */
    public interface Listener {

        /** A new screen frame arrived (protocol v1 only). */
        void onFrame(FrameUpdate frame);

        /** A part of a tile frame arrived (protocol v2 only). */
        default void onTiles(TileUpdate tiles) {}

        /** The agent announced which transport carries the picture (protocol v3 only). */
        default void onTransportState(TransportState state) {}

        /**
         * A rectangle of the picture over UDP was completed (protocol v3 only). May come before the
         * {@code TransportState} that announces UDP (the datagrams can overtake it). Report its end with
         * {@link AgentConnection#udpRectApplied} or {@link AgentConnection#udpRectDropped}.
         */
        default void onUdpRect(CompleteRect rect) {}

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
    private final int protocolVersion;

    private AgentConnection(Channel channel, EventLoopGroup group, Handler handler, ScreenInfo screenInfo,
                            int protocolVersion) {
        this.channel = channel;
        this.group = group;
        this.handler = handler;
        this.screenInfo = screenInfo;
        this.protocolVersion = protocolVersion;
    }

    /**
     * Connects to an agent and authenticates with its pairing code; whitespace in the code is ignored.
     *
     * @return completes after the agent's {@code Hello} and {@link ScreenInfo}; fails with
     *     {@link AgentRejectedException} when the agent says {@code Bye} first, with {@link TimeoutException}
     *     when the agent does not complete it within 10 s (or stays completely silent for 6 s, the idle timeout),
     *     or with the {@link IOException} of the failed connection
     * @throws IllegalArgumentException for an invalid developer property ({@link #defaultProtocolVersion()},
     *     {@value LinkImpairment#DEV_PROPERTY})
     */
    public static CompletableFuture<AgentConnection> connect(String host, int port, String pairingCode,
                                                             Listener listener) {
        return connect(host, port, pairingCode, defaultProtocolVersion(), listener);
    }

    /**
     * Like {@link #connect(String, int, String, Listener)}, proposing {@code protocolVersion} instead of
     * {@link #defaultProtocolVersion()}; the agent may answer with any version up to it. The v3 UDP channel is
     * impaired as the developer property {@value LinkImpairment#DEV_PROPERTY} says (none when absent; a
     * {@code WARNING} is logged when set).
     */
    static CompletableFuture<AgentConnection> connect(String host, int port, String pairingCode, int protocolVersion,
                                                      Listener listener) {
        return connect(host, port, pairingCode, protocolVersion, listener, LinkImpairment.fromSystemProperty());
    }

    /** Like {@link #connect(String, int, String, int, Listener)}, impairing the v3 UDP channel (tests). */
    static CompletableFuture<AgentConnection> connect(String host, int port, String pairingCode, int protocolVersion,
                                                      Listener listener, LinkImpairment udpImpairment) {
        if (protocolVersion < 1 || protocolVersion > HIGHEST_PROTOCOL_VERSION) {
            throw new IllegalArgumentException(
                    "protocolVersion must be 1.." + HIGHEST_PROTOCOL_VERSION + ", was " + protocolVersion);
        }
        Objects.requireNonNull(host, "host");
        Objects.requireNonNull(pairingCode, "pairingCode");
        Objects.requireNonNull(listener, "listener");
        Objects.requireNonNull(udpImpairment, "udpImpairment");
        String code = pairingCode.replaceAll("\\s", "");
        CompletableFuture<AgentConnection> result = new CompletableFuture<>();
        EventLoopGroup group = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
        Handler handler = new Handler(code, protocolVersion, listener, result, group, udpImpairment);
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
     * The protocol version negotiated with the agent: {@code 1} (JPEG frames), {@code 2} (tiles) or {@code 3}
     * (tiles plus the UDP channel).
     */
    public int protocolVersion() {
        return protocolVersion;
    }

    /** The latest {@code TransportState} of a v3 agent; {@code null} until the first one (and always on v1/v2). */
    public TransportState transportState() {
        return handler.transportState;
    }

    /** The UDP channel of a v3 session; {@code null} until the agent's {@code UdpSetup} (and always on v1/v2). */
    ViewerUdpLink udpLink() {
        return handler.udpLink;
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
     * The UDP rectangle {@code rectId} was applied (or skipped as having nothing newer): the viewer's next UDP
     * {@code ACK} reports it. Any thread, never blocks; ignored without a UDP channel or once closed.
     */
    void udpRectApplied(long rectId) {
        ViewerUdpLink link = handler.udpLink;
        if (link != null) {
            link.applied(rectId);
        }
    }

    /** The UDP rectangle {@code rectId} was discarded; reported like {@link #udpRectApplied}. */
    void udpRectDropped(long rectId) {
        ViewerUdpLink link = handler.udpLink;
        if (link != null) {
            link.dropped(rectId);
        }
    }

    /** Tells a v2 agent that frame {@code sequence} has been applied; nothing is sent on v1 or once closed. */
    public void sendFrameAck(long sequence) {
        if (protocolVersion >= 2) {
            channel.writeAndFlush(new FrameAck(sequence));
        }
    }

    /** Asks a v2 agent for another stream size; nothing is sent on v1 or once closed. */
    public void sendStreamConfig(StreamConfig config) {
        Objects.requireNonNull(config, "config");
        if (protocolVersion >= 2) {
            channel.writeAndFlush(config);
        }
    }

    /**
     * Ends the connection with {@code Bye(reason)}; the listener gets {@code onClosed(reason)} unless the
     * connection was already ending. Never blocks, so any thread may call it; it does not wait for the shutdown.
     */
    public void abort(String reason) {
        Objects.requireNonNull(reason, "reason");
        try {
            channel.eventLoop().execute(() -> handler.sayByeAndClose(channel, reason));
        } catch (RejectedExecutionException e) {
            // The connection already ended and its thread is gone.
        }
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
        private final int proposedVersion;
        private final Listener listener;
        private final CompletableFuture<AgentConnection> result;
        private final EventLoopGroup group;
        private final LinkImpairment udpImpairment;

        private ScheduledFuture<?> handshakeTimeout;
        private boolean helloReceived;
        /** The agent's answer to the proposed version; valid once {@code helloReceived}. */
        private int version;
        /** Set once the future completed with a connection; from then on {@code onClosed} is owed. */
        private boolean established;
        private boolean closedReported;
        /** Reason of the {@code Bye} that ends the connection, from either side. */
        private String byeReason;
        private boolean udpSetupSeen;
        /** Written on the event loop only; read by the accessors on any thread. */
        private volatile ViewerUdpLink udpLink;
        private volatile TransportState transportState;

        Handler(String code, int proposedVersion, Listener listener, CompletableFuture<AgentConnection> result,
                EventLoopGroup group, LinkImpairment udpImpairment) {
            this.code = code;
            this.proposedVersion = proposedVersion;
            this.listener = listener;
            this.result = result;
            this.group = group;
            this.udpImpairment = udpImpairment;
        }

        @Override
        public void channelActive(ChannelHandlerContext ctx) throws Exception {
            ctx.writeAndFlush(new Hello(proposedVersion, Hello.Role.VIEWER, code));
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
                        && hello.protocolVersion() <= proposedVersion -> {
                    helloReceived = true;
                    version = hello.protocolVersion();
                }
                case ScreenInfo info when helloReceived -> {
                    handshakeTimeout.cancel(false);
                    AgentConnection connection = new AgentConnection(ctx.channel(), group, this, info, version);
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
                case FrameUpdate frame when version == 1 -> {
                    try {
                        listener.onFrame(frame);
                    } catch (RuntimeException e) {
                        LOG.log(Level.ERROR, "Frame listener failed", e);
                    }
                }
                case TileUpdate tiles when version >= 2 -> {
                    try {
                        listener.onTiles(tiles);
                    } catch (RuntimeException e) {
                        LOG.log(Level.ERROR, "Tile listener failed", e);
                    }
                }
                case UdpSetup setup when version >= 3 -> openUdp(ctx, setup);
                case TransportState state when version >= 3 -> {
                    transportState = state;
                    try {
                        listener.onTransportState(state);
                    } catch (RuntimeException e) {
                        LOG.log(Level.ERROR, "Transport state listener failed", e);
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

        /** Event loop: opens the UDP channel to the TCP peer's address; a repeated {@code UdpSetup} is ignored. */
        private void openUdp(ChannelHandlerContext ctx, UdpSetup setup) {
            if (udpSetupSeen) {
                LOG.log(Level.DEBUG, "Ignoring a second UdpSetup: {0}", setup);
                return;
            }
            udpSetupSeen = true;
            // The address the TCP connection reached (not a re-resolved host name): the agent's UDP port is there.
            InetSocketAddress tcpPeer = (InetSocketAddress) ctx.channel().remoteAddress();
            InetSocketAddress agentUdp = new InetSocketAddress(tcpPeer.getAddress(), setup.udpPort());
            try {
                // A persistently failing UDP receiver would freeze the picture: end the session (same event loop).
                udpLink = ViewerUdpLink.open(ctx.channel().eventLoop(), agentUdp, setup, udpImpairment,
                        this::udpRect, () -> sayByeAndClose(ctx.channel(), PROTOCOL_ERROR));
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "Cannot open the UDP channel, continuing over TCP", e);
            }
        }

        /**
         * Event loop (the UDP link shares it): a completed UDP rectangle. A throwing listener is logged by the link,
         * which reports the rectangle dropped.
         */
        private void udpRect(CompleteRect rect) {
            listener.onUdpRect(rect);
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
            ViewerUdpLink link = udpLink;
            if (link != null) {
                link.close(); // on the event loop: does not wait
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

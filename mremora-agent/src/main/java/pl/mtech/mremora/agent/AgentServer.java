package pl.mtech.mremora.agent;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoop;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.util.concurrent.ScheduledFuture;
import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import pl.mtech.mremora.protocol.Bye;
import pl.mtech.mremora.protocol.Hello;
import pl.mtech.mremora.protocol.ScreenInfo;
import pl.mtech.mremora.protocol.netty.MessagePipeline;

/**
 * TCP server of the agent: admits at most one unauthenticated connection at a time, checks its pairing code
 * and turns an accepted connection into the single remote session.
 *
 * <p>All mutable state, including {@link PairingCodes}, lives on the one worker event-loop thread.
 */
public final class AgentServer implements AutoCloseable {

    /** Largest inbound frame the agent accepts; viewers only send small messages. */
    static final int MAX_INBOUND_FRAME = 128 * 1024;

    static final String INVALID_CODE = "invalid pairing code";
    static final String TOO_MANY_ATTEMPTS = "too many attempts";
    static final String LOCKED = "locked";
    static final String BUSY = "busy";
    static final String PROTOCOL_ERROR = "protocol error";
    static final String TIMEOUT = "timeout";
    static final String ENDED_BY_USER = "ended by user";
    static final String AGENT_ERROR = "agent error";
    static final String SHUTTING_DOWN = "agent shutting down";

    private static final Logger LOG = System.getLogger(AgentServer.class.getName());
    private static final Duration UI_REFRESH = Duration.ofSeconds(1);
    private static final Duration SHUTDOWN_WAIT = Duration.ofSeconds(5);

    private final AgentConfig config;
    private final ScreenCapturer capturer;
    private final InputInjector injector;
    private final PairingCodes codes;
    private final AgentUi ui;

    private EventLoopGroup boss;
    private EventLoopGroup worker;
    private EventLoop loop;
    private Channel serverChannel;
    private volatile boolean closing;

    // Event-loop state: touched only on `loop`.
    private Channel pending;
    private AgentSession session;
    private String shownCode;
    private ScheduledFuture<?> uiTick;

    public AgentServer(AgentConfig config, ScreenCapturer capturer, InputInjector injector,
                       PairingCodes codes, AgentUi ui) {
        this.config = Objects.requireNonNull(config, "config");
        this.capturer = Objects.requireNonNull(capturer, "capturer");
        this.injector = Objects.requireNonNull(injector, "injector");
        this.codes = Objects.requireNonNull(codes, "codes");
        this.ui = Objects.requireNonNull(ui, "ui");
    }

    /**
     * Binds on all interfaces and shows the first pairing code.
     *
     * @return the port actually bound (useful with port {@code 0})
     */
    public synchronized int start() {
        if (worker != null) {
            throw new IllegalStateException("already started");
        }
        boss = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
        worker = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
        loop = worker.next();
        try {
            serverChannel = new ServerBootstrap()
                    .group(boss, worker)
                    .channel(NioServerSocketChannel.class)
                    .childOption(ChannelOption.TCP_NODELAY, true)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            MessagePipeline.install(ch.pipeline(), MAX_INBOUND_FRAME,
                                    config.heartbeatInterval(), config.idleTimeout());
                            ch.pipeline().addLast("handshake", new HandshakeHandler());
                        }
                    })
                    .bind(config.port())
                    .syncUninterruptibly()
                    .channel();
            loop.submit(() -> {
                refreshUi();
                uiTick = loop.scheduleAtFixedRate(this::refreshUi,
                        UI_REFRESH.toMillis(), UI_REFRESH.toMillis(), TimeUnit.MILLISECONDS);
            }).syncUninterruptibly();
        } catch (Throwable e) { // Netty rethrows checked causes (e.g. BindException) unchecked
            if (serverChannel != null) {
                serverChannel.close().awaitUninterruptibly(SHUTDOWN_WAIT.toMillis());
            }
            shutdownGroups();
            boss = null;
            worker = null;
            loop = null;
            serverChannel = null;
            throw e;
        }
        return ((InetSocketAddress) serverChannel.localAddress()).getPort();
    }

    /**
     * Stops accepting, ends the session and any pending connection with {@code Bye("agent shutting down")},
     * waits until the session released held mouse buttons, then stops.
     * A no-op when the server never started or {@link #start()} failed. Idempotent and safe from any thread
     * (e.g. a shutdown hook racing a quit button): a concurrent call waits until the first one has finished.
     * Never call it from the event loop.
     */
    @Override
    public synchronized void close() {
        if (closing) {
            return;
        }
        closing = true;
        if (worker == null) {
            return;
        }
        // First stop accepting, so no connection can slip in after the existing ones were told to go.
        serverChannel.close().awaitUninterruptibly(SHUTDOWN_WAIT.toMillis());
        List<Runnable> waits = loop.submit(this::closeConnections).syncUninterruptibly().getNow();
        waits.forEach(Runnable::run);
        shutdownGroups();
    }

    /** Event loop: says goodbye to every connection; returns waits for each of them to finish. */
    private List<Runnable> closeConnections() {
        if (uiTick != null) {
            uiTick.cancel(false);
        }
        List<Runnable> waits = new ArrayList<>();
        if (session != null) {
            AgentSession s = session;
            s.end(SHUTTING_DOWN);
            waits.add(() -> s.awaitInputStopped(SHUTDOWN_WAIT));
            waits.add(() -> s.channel().closeFuture().awaitUninterruptibly(SHUTDOWN_WAIT.toMillis()));
        }
        if (pending != null) {
            Channel ch = pending;
            ch.writeAndFlush(new Bye(SHUTTING_DOWN)).addListener(ChannelFutureListener.CLOSE);
            waits.add(() -> ch.closeFuture().awaitUninterruptibly(SHUTDOWN_WAIT.toMillis()));
        }
        return waits;
    }

    private void shutdownGroups() {
        var bossDone = boss.shutdownGracefully(0, SHUTDOWN_WAIT.toMillis(), TimeUnit.MILLISECONDS);
        var workerDone = worker.shutdownGracefully(0, SHUTDOWN_WAIT.toMillis(), TimeUnit.MILLISECONDS);
        bossDone.awaitUninterruptibly();
        workerDone.awaitUninterruptibly();
    }

    /** Event loop: shows the code or the lockout while no session runs. */
    private void refreshUi() {
        if (closing || session != null) {
            return;
        }
        try {
            switch (codes.status()) {
                case PairingCodes.Available a -> {
                    if (!a.code().equals(shownCode)) {
                        shownCode = a.code();
                        ui.showCode(a.code());
                    }
                }
                case PairingCodes.Locked l -> {
                    shownCode = null;
                    ui.showLocked(l.remaining());
                }
            }
        } catch (RuntimeException e) {
            LOG.log(Level.ERROR, "Updating the agent UI failed", e);
        }
    }

    /** Event loop: a verified viewer becomes the session; frames start after the handshake reply. */
    private void startSession(ChannelHandlerContext ctx, Hello hello, ScreenInfo screen) {
        Channel ch = ctx.channel();
        int version = Math.min(config.maxProtocolVersion(), hello.protocolVersion());
        ctx.write(new Hello(version, Hello.Role.AGENT, ""));
        ctx.writeAndFlush(screen);
        AgentSession s = new AgentSession(ch, capturer, injector, config.maxFps(), version, () -> sessionEnded(ch));
        session = s;
        shownCode = null;
        ctx.pipeline().replace(ctx.handler(), "session", s);
        s.start();
        String viewer = ((InetSocketAddress) ch.remoteAddress()).getAddress().getHostAddress();
        LOG.log(Level.DEBUG, "Session started with {0}", viewer);
        try {
            ui.showSession(viewer, () -> s.requestEnd(ENDED_BY_USER));
        } catch (RuntimeException e) {
            // No indicator means no session: the user must always see that someone is connected.
            LOG.log(Level.ERROR, "Showing the session failed", e);
            s.end(AGENT_ERROR);
        }
    }

    /** Event loop: the session on {@code ch} ended and released its buttons; wait for the next viewer. */
    private void sessionEnded(Channel ch) {
        if (session == null || session.channel() != ch) {
            return;
        }
        session = null;
        refreshUi();
    }

    /** First phase of every connection: admission, then exactly one {@code Hello} check. */
    private final class HandshakeHandler extends ChannelInboundHandlerAdapter {

        /** A decision was made; everything that arrives later is ignored. */
        private boolean decided;
        private ScheduledFuture<?> timeout;

        @Override
        public void channelActive(ChannelHandlerContext ctx) throws Exception {
            if (closing) {
                reject(ctx, SHUTTING_DOWN);
                return;
            }
            if (session != null || pending != null) {
                reject(ctx, BUSY);
                return;
            }
            pending = ctx.channel();
            timeout = ctx.executor().schedule(() -> reject(ctx, TIMEOUT),
                    config.handshakeTimeout().toMillis(), TimeUnit.MILLISECONDS);
            super.channelActive(ctx);
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            if (decided) {
                return;
            }
            if (!(msg instanceof Hello hello) || hello.role() != Hello.Role.VIEWER) {
                reject(ctx, PROTOCOL_ERROR);
                return;
            }
            ScreenInfo screen;
            try {
                screen = capturer.info();
            } catch (RuntimeException e) {
                LOG.log(Level.ERROR, "Reading screen info failed", e);
                reject(ctx, AGENT_ERROR);
                return;
            }
            PairingCodes.Result result = codes.verify(hello.pairingCode());
            switch (result) {
                case ACCEPTED -> {
                    finish(ctx);
                    startSession(ctx, hello, screen);
                }
                case REJECTED -> reject(ctx, INVALID_CODE);
                case TOO_SOON -> reject(ctx, TOO_MANY_ATTEMPTS);
                case LOCKED -> reject(ctx, LOCKED);
            }
            if (result != PairingCodes.Result.ACCEPTED) {
                refreshUi();
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            if (decided) {
                LOG.log(Level.DEBUG, "Ignoring error after handshake decision: {0}", cause.toString());
                return;
            }
            if (cause instanceof IOException) {
                LOG.log(Level.DEBUG, "Connection failed during handshake: {0}", cause.toString());
                finish(ctx);
                ctx.close();
                return;
            }
            LOG.log(Level.DEBUG, "Protocol error during handshake: {0}", cause.toString());
            reject(ctx, PROTOCOL_ERROR);
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) throws Exception {
            finish(ctx);
            super.channelInactive(ctx);
        }

        private void reject(ChannelHandlerContext ctx, String reason) {
            if (decided) {
                return;
            }
            finish(ctx);
            LOG.log(Level.DEBUG, "Rejecting {0}: {1}", ctx.channel().remoteAddress(), reason);
            ctx.writeAndFlush(new Bye(reason)).addListener(ChannelFutureListener.CLOSE);
        }

        /** Marks the decision and frees the unauthenticated slot if this connection holds it. */
        private void finish(ChannelHandlerContext ctx) {
            decided = true;
            if (timeout != null) {
                timeout.cancel(false);
            }
            if (pending == ctx.channel()) {
                pending = null;
            }
        }
    }
}

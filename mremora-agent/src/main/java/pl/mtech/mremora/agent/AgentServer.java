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
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import pl.mtech.mremora.protocol.Bye;
import pl.mtech.mremora.protocol.Hello;
import pl.mtech.mremora.protocol.ScreenInfo;
import pl.mtech.mremora.protocol.TransportState;
import pl.mtech.mremora.protocol.UdpSetup;
import pl.mtech.mremora.protocol.netty.LinkImpairment;
import pl.mtech.mremora.protocol.netty.MessagePipeline;
import pl.mtech.mremora.protocol.udp.UdpImageSender;

/**
 * TCP server of the agent: admits at most one unauthenticated connection at a time, checks its pairing code
 * and turns an accepted connection into the single remote session.
 *
 * <p>Protocol v3 (offered only when {@code maxProtocolVersion >= 3} and the UDP socket could be bound and has not
 * died since): after {@code Hello} and {@code ScreenInfo} the agent sends {@code UdpSetup}, runs the UDP probe phase
 * of a {@link UdpSessionLink} and, once it decided, announces the transport and only then starts capturing: a
 * successful probe round trip gives {@code TransportState(UDP, 0)} and the picture as {@code TILE} packets through a
 * {@link UdpImageChannel}; otherwise {@code TransportState(TCP, 0)} and the v2 tile stream over TCP. If UDP dies
 * later in the session, the picture stands still (falling back is a later stage; v3 is off by default).
 *
 * <p>All mutable state, including {@link PairingCodes}, lives on the one worker event-loop thread; the UDP socket
 * runs on that same thread.
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
    /** Session ids and keys; one instance for the life of the server. Used on the event loop only. */
    private final SecureRandom random = new SecureRandom();

    // Set before start(); read on the event loop afterwards.
    private LinkImpairment udpImpairment = LinkImpairment.NONE;
    private Duration udpStartupTimeout = UdpStartup.TIMEOUT;

    private EventLoopGroup boss;
    private EventLoopGroup worker;
    private EventLoop loop;
    private Channel serverChannel;
    private volatile boolean closing;
    /** {@code null} without UDP: v3 disabled by config, or the bind failed. */
    private volatile UdpEndpoint udp;
    private volatile TransportState.Mode lastUdpOutcome;
    /** Latest snapshot of the UDP image path; written on the event loop, read anywhere. */
    private volatile UdpImageStats imageStats = UdpImageStats.ZERO;

    // Event-loop state: touched only on `loop`.
    private Channel pending;
    private AgentSession session;
    /** UDP link of the current v3 session until it ends; {@code null} otherwise. */
    private UdpSessionLink udpLink;
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
            if (config.maxProtocolVersion() >= 3) {
                // Same number as TCP; with port 0 both pick their own free port. Bound first, so no viewer can
                // be admitted before the endpoint is known. A failed bind logs a WARNING and caps us at v2.
                udp = UdpEndpoint.bind(worker, config.port(), udpImpairment).orElse(null);
            }
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
            UdpEndpoint endpoint = udp;
            if (endpoint != null) {
                // Registered only once started, so the failure path below closing it is not reported.
                endpoint.onUnexpectedClose(() -> udpLost(endpoint));
            }
            loop.submit(() -> {
                refreshUi();
                uiTick = loop.scheduleAtFixedRate(this::refreshUi,
                        UI_REFRESH.toMillis(), UI_REFRESH.toMillis(), TimeUnit.MILLISECONDS);
            }).syncUninterruptibly();
        } catch (Throwable e) { // Netty rethrows checked causes (e.g. BindException) unchecked
            if (serverChannel != null) {
                serverChannel.close().awaitUninterruptibly(SHUTDOWN_WAIT.toMillis());
            }
            if (udp != null) {
                udp.close(); // before the groups: closing needs a live loop
                udp = null;
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
     * Event loop: the UDP socket died on its own (a fatal read error); later sessions negotiate at most v2. A session
     * waiting for its UDP decision still gets it (TCP, by the startup timeout).
     */
    private void udpLost(UdpEndpoint endpoint) {
        if (closing || udp != endpoint) {
            return;
        }
        udp = null;
        LOG.log(Level.ERROR, "UDP socket closed unexpectedly, offering at most protocol v2 from now on");
    }

    /** Tests: the UDP endpoint, {@code null} when the server runs without UDP. */
    UdpEndpoint udpEndpoint() {
        return udp;
    }

    /** The bound UDP port, {@code -1} when the server runs without UDP. */
    int udpPort() {
        UdpEndpoint u = udp;
        return u == null ? -1 : u.port();
    }

    /**
     * Before {@link #start()}: impairs the UDP socket. For tests and developer mode (property
     * {@value LinkImpairment#DEV_PROPERTY}), never for production use.
     *
     * @throws IllegalStateException once started
     */
    public synchronized void udpImpairment(LinkImpairment impairment) {
        requireNotStarted();
        this.udpImpairment = Objects.requireNonNull(impairment, "impairment");
    }

    /**
     * Before {@link #start()}: how long a v3 session waits for a UDP round trip, default {@link UdpStartup#TIMEOUT}.
     * For tests and developer mode.
     *
     * @throws IllegalStateException once started
     */
    public synchronized void udpStartupTimeout(Duration timeout) {
        requireNotStarted();
        this.udpStartupTimeout = Objects.requireNonNull(timeout, "timeout");
    }

    /**
     * Probe outcome of the latest v3 session; empty until it decided (or when it ended undecided). Diagnostics and
     * tests (e.g. viewer interop tests in another package); set before the session's {@code TransportState} is sent.
     */
    public Optional<TransportState.Mode> lastUdpOutcome() {
        return Optional.ofNullable(lastUdpOutcome);
    }

    /**
     * Statistics of the picture over UDP, for diagnostics and tests (e.g. viewer end-to-end tests in another
     * package). Any thread.
     *
     * @return empty while the server runs without UDP (not started, v3 disabled, bind failed, or the socket died);
     *     otherwise the latest snapshot the UDP image path published (cumulative for the server's life,
     *     {@link UdpImageStats#ZERO} before any) with the injector's drop count and the datagrams sent read live
     */
    public Optional<UdpImageStats> udpImageStats() {
        UdpEndpoint u = udp;
        if (u == null) {
            return Optional.empty();
        }
        return Optional.of(imageStats.withSocketCounters(u.impairmentDrops(), u.datagramsSent()));
    }

    /**
     * Event loop (the UDP image path): publishes a new cumulative snapshot for {@link #udpImageStats()}; its
     * {@code injectorDrops} and {@code datagramsSent} are ignored (read live from the socket).
     */
    void publishUdpImageStats(UdpImageStats stats) {
        imageStats = Objects.requireNonNull(stats, "stats");
    }

    private void requireNotStarted() {
        if (worker != null) {
            throw new IllegalStateException("already started");
        }
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
        UdpEndpoint endpoint = udp;
        if (endpoint != null) {
            endpoint.close(); // the session already detached its link; this frees the port
        }
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
        UdpEndpoint endpoint = udp;
        int maxOffered = endpoint != null ? config.maxProtocolVersion() : Math.min(config.maxProtocolVersion(), 2);
        int version = Math.min(maxOffered, hello.protocolVersion());
        ctx.write(new Hello(version, Hello.Role.AGENT, ""));
        UdpSessionLink link = null;
        if (version >= 3) {
            lastUdpOutcome = null;
            int sessionId = random.nextInt();
            byte[] key = new byte[UdpSetup.KEY_LENGTH];
            random.nextBytes(key);
            link = new UdpSessionLink(endpoint, sessionId, key, System::nanoTime, udpStartupTimeout,
                    mode -> udpDecided(ch, mode));
            ctx.write(screen);
            ctx.writeAndFlush(new UdpSetup(endpoint.port(), sessionId, key));
        } else {
            ctx.writeAndFlush(screen);
        }
        UdpSessionLink sessionLink = link;
        Runnable onEnd = sessionLink == null ? () -> { } : () -> {
            if (udpLink == sessionLink) {
                udpLink = null;
            }
            endpoint.detach(sessionLink); // also closes it: no decision, no probe after the end
        };
        AgentSession s = new AgentSession(ch, capturer, injector, config.maxFps(), version, onEnd,
                () -> sessionEnded(ch));
        session = s;
        shownCode = null;
        ctx.pipeline().replace(ctx.handler(), "session", s);
        if (sessionLink == null) {
            s.start();
        } else {
            try {
                // Back to back on the loop: no datagram or timer can fall between attach and start.
                udpLink = sessionLink;
                endpoint.attach(sessionLink);
                sessionLink.start();
            } catch (RuntimeException e) {
                LOG.log(Level.ERROR, "Starting the UDP link failed", e);
                s.end(AGENT_ERROR);
                return;
            }
        }
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

    /**
     * Event loop: the UDP probe phase of the session on {@code ch} decided. Never called once that session ended
     * (its end closes the link); the session check guards a stale call all the same.
     */
    private void udpDecided(Channel ch, TransportState.Mode mode) {
        lastUdpOutcome = mode;
        if (mode == TransportState.Mode.UDP) {
            UdpSessionLink link = udpLink;
            String rtt = link == null || link.lastRttNanos().isEmpty() ? "?"
                    : String.valueOf(TimeUnit.NANOSECONDS.toMillis(link.lastRttNanos().getAsLong()));
            LOG.log(Level.INFO, "UDP usable, RTT {0} ms", rtt);
        } else {
            LOG.log(Level.INFO, "UDP unavailable, image over TCP");
        }
        if (session == null || session.channel() != ch) {
            return;
        }
        AgentSession s = session;
        UdpSessionLink link = udpLink;
        if (mode != TransportState.Mode.UDP || link == null) {
            s.announceAndStart(new TransportState(TransportState.Mode.TCP, 0));
            return;
        }
        UdpImageChannel image;
        try {
            // On the link's loop (this one), over the link's peer: probes and fragments share one numbering.
            image = new UdpImageChannel(link, new UdpImageSender(link.peer(), System.nanoTime()), imageStats,
                    this::publishUdpImageStats, () -> s.end(AGENT_ERROR));
        } catch (RuntimeException e) {
            LOG.log(Level.ERROR, "Starting the picture over UDP failed", e);
            s.end(AGENT_ERROR);
            return;
        }
        s.announceAndStartUdp(image);
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

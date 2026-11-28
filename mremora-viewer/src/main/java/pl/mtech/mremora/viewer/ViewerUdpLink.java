package pl.mtech.mremora.viewer;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoop;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.socket.DatagramChannel;
import io.netty.channel.socket.DatagramPacket;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.util.concurrent.ScheduledFuture;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import pl.mtech.mremora.protocol.UdpSetup;
import pl.mtech.mremora.protocol.netty.ImpairedDatagramHandler;
import pl.mtech.mremora.protocol.netty.LinkImpairment;
import pl.mtech.mremora.protocol.udp.CompleteRect;
import pl.mtech.mremora.protocol.udp.UdpImageReceiver;
import pl.mtech.mremora.protocol.udp.UdpPacket;
import pl.mtech.mremora.protocol.udp.UdpPacket.Direction;
import pl.mtech.mremora.protocol.udp.UdpPeer;

/**
 * The viewer's end of the signed UDP channel of one v3 session. The socket is connected to the agent's address, so
 * the system drops datagrams from anyone else. Probes the agent at once, then every {@link UdpPeer#PROBE_FAST} until
 * the first {@code PROBE_REPLY}, then every {@link UdpPeer#PROBE_SLOW} (NAT keepalive), and answers every agent
 * {@code PROBE}.
 *
 * <p>The picture (protocol v3, image over UDP): {@code TILE} fragments go to a {@link UdpImageReceiver}, every
 * rectangle it completes to the rectangle sink (on the event loop); the caller reports how each ended with
 * {@link #applied} or {@link #dropped}, and the receiver's {@code ACK}s tell the agent. The {@code ACK}s are sent at
 * the receiver's {@link UdpImageReceiver#nextDeadlineNanos deadlines} by a one-shot timer, re-armed after every
 * opened packet and every report. Agent {@code PROBE}s are answered and acknowledged.
 *
 * <p>Inbound datagrams are checked in the order of the plan (codec: length, magic, direction, session, type, MAC;
 * then the replay window), each opened exactly once, and anything invalid is dropped silently; no per-packet error
 * closes the socket. All state lives on the channel's event loop; {@link #established()}, {@link #lastRttNanos()},
 * {@link #closed()}, {@link #applied} and {@link #dropped} are safe from any thread. {@link #open} and
 * {@link #close()} never block on the event loop.
 *
 * <p>A throw of the receiver while polling it (ACKs, assembly expiry) or while handling an opened packet is logged
 * (the first of a streak as an ERROR, the rest at DEBUG); a poll is retried {@value #RETRY_NANOS} ns later.
 * {@value #MAX_CONSECUTIVE_FAILURES} consecutive failures of the same kind (a success of that kind resets its count)
 * close the link and call {@code onFailure} once, which ends the session.
 */
final class ViewerUdpLink implements AutoCloseable {

    private static final Logger LOG = System.getLogger(ViewerUdpLink.class.getName());
    private static final Duration CLOSE_WAIT = Duration.ofSeconds(5);
    /** Retry delay of the poll timer when polling the receiver failed. */
    static final long RETRY_NANOS = 25_000_000L;
    /** Consecutive failures of polling, or of handling packets, that end the session. */
    static final int MAX_CONSECUTIVE_FAILURES = 3;

    /** Tests: fault injection in front of the receiver calls; event loop. */
    interface Faults {
        Faults NONE = new Faults() { };

        /** Before polling the receiver; a throw counts as a poll failure. */
        default void beforePoll() { }

        /** Before handling an opened packet; a throw counts as a packet failure. */
        default void beforePacket() { }
    }

    private final Channel channel;
    private final EventLoop loop;
    private final InetSocketAddress agent;
    /** Event loop only. */
    private final UdpPeer peer;
    /** Event loop only. */
    private final UdpImageReceiver receiver;
    private final Consumer<CompleteRect> rects;
    private final Runnable onFailure;

    // Event-loop state.
    private ScheduledFuture<?> probeTimer;
    /** One-shot timer of the receiver's next deadline ({@link #pollAtNanos}); {@code null} when none is armed. */
    private ScheduledFuture<?> pollTimer;
    private long pollAtNanos;
    private int pollFailures;
    private int packetFailures;
    private boolean failed;
    private volatile Faults faults = Faults.NONE;

    // Written on the event loop only, read anywhere.
    private volatile boolean established;
    private volatile long lastRttNanos = -1;
    /** Set by {@link #close()} (any thread) or when the socket closed; stops probing and handling. */
    private volatile boolean closing;

    private ViewerUdpLink(Channel channel, InetSocketAddress agent, UdpSetup setup, Consumer<CompleteRect> rects,
                          Runnable onFailure) {
        this.channel = channel;
        this.loop = channel.eventLoop();
        this.agent = agent;
        this.peer = new UdpPeer(Direction.VIEWER, setup.sessionId(), setup.key());
        this.receiver = new UdpImageReceiver(peer);
        this.rects = rects;
        this.onFailure = onFailure;
    }

    /** Like {@link #open(EventLoopGroup, InetSocketAddress, UdpSetup, LinkImpairment, Consumer)}, ignoring rects. */
    static ViewerUdpLink open(EventLoopGroup group, InetSocketAddress agent, UdpSetup setup,
                              LinkImpairment impairment) {
        return open(group, agent, setup, impairment, rect -> {});
    }

    /**
     * Like {@link #open(EventLoopGroup, InetSocketAddress, UdpSetup, LinkImpairment, Consumer, Runnable)} without a
     * failure callback (a persistently failing receiver only closes the link).
     */
    static ViewerUdpLink open(EventLoopGroup group, InetSocketAddress agent, UdpSetup setup,
                              LinkImpairment impairment, Consumer<CompleteRect> rects) {
        return open(group, agent, setup, impairment, rects, () -> {});
    }

    /**
     * Opens a UDP socket on {@code group}, connected to {@code agent}, and starts probing once it is connected.
     * Does not wait, so it may be called on an event loop; a failure to open is logged and leaves the link closed.
     *
     * @param agent     the agent's UDP address (its host and {@code setup.udpPort()})
     * @param rects     gets every completed rectangle of the picture, on the event loop; must not block
     * @param onFailure run once on the event loop when the receiver failed persistently (the link is closed by then);
     *                  the session must end
     */
    static ViewerUdpLink open(EventLoopGroup group, InetSocketAddress agent, UdpSetup setup,
                              LinkImpairment impairment, Consumer<CompleteRect> rects, Runnable onFailure) {
        Objects.requireNonNull(group, "group");
        Objects.requireNonNull(agent, "agent");
        Objects.requireNonNull(setup, "setup");
        Objects.requireNonNull(impairment, "impairment");
        Objects.requireNonNull(rects, "rects");
        Objects.requireNonNull(onFailure, "onFailure");
        Receiver receiver = new Receiver();
        ChannelFuture connected = new Bootstrap()
                .group(group)
                .channel(NioDatagramChannel.class)
                .handler(new ChannelInitializer<DatagramChannel>() {
                    @Override
                    protected void initChannel(DatagramChannel c) {
                        if (!impairment.equals(LinkImpairment.NONE)) {
                            c.pipeline().addLast("impairment", new ImpairedDatagramHandler(impairment));
                        }
                        c.pipeline().addLast("udp", receiver);
                    }
                })
                .connect(agent);
        ViewerUdpLink link = new ViewerUdpLink(connected.channel(), agent, setup, rects, onFailure);
        receiver.link = link;
        connected.channel().closeFuture().addListener(f -> link.closedOnLoop());
        connected.addListener(f -> {
            if (f.isSuccess()) {
                link.startProbing();
            } else {
                LOG.log(Level.WARNING, "Cannot open UDP to " + agent + ", continuing over TCP: " + f.cause());
                link.channel.close();
            }
        });
        return link;
    }

    /** Whether the agent answered one of this link's probes. */
    boolean established() {
        return established;
    }

    /** Round-trip time of the latest answered probe. */
    OptionalLong lastRttNanos() {
        long rtt = lastRttNanos;
        return rtt < 0 ? OptionalLong.empty() : OptionalLong.of(rtt);
    }

    /**
     * The rectangle {@code rectId} was applied (or skipped as having nothing newer); the next {@code ACK} reports
     * it. Any thread; ignored once the link is closing.
     */
    void applied(long rectId) {
        onLoop(() -> receiver.applied(rectId));
    }

    /** The rectangle {@code rectId} was discarded; reported in the next {@code ACK}s. Like {@link #applied}. */
    void dropped(long rectId) {
        onLoop(() -> receiver.dropped(rectId));
    }

    /** Runs a report on the event loop, then re-arms the poll timer (the report makes an {@code ACK} due). */
    private void onLoop(Runnable report) {
        if (closing) {
            return;
        }
        try {
            loop.execute(() -> {
                if (closing) {
                    return;
                }
                report.run();
                service(false);
            });
        } catch (RejectedExecutionException e) {
            // The event loop is gone: so is the session.
        }
    }

    /** Tests: replaces the fault injection ({@link Faults#NONE} by default). Any thread. */
    void faults(Faults f) {
        faults = Objects.requireNonNull(f, "faults");
    }

    /** Whether the socket is closed (by {@link #close()} or otherwise). */
    boolean closed() {
        return !channel.isOpen();
    }

    /**
     * Stops probing and closes the socket. Idempotent, any thread; off the event loop it waits (bounded) until the
     * port is released, on it it does not wait.
     */
    @Override
    public void close() {
        closing = true;
        ChannelFuture closeFuture = channel.close();
        if (!loop.inEventLoop()) {
            closeFuture.awaitUninterruptibly(CLOSE_WAIT.toMillis());
        }
    }

    /** Event loop, from the connect listener: first probe at once, then the fast cadence. */
    private void startProbing() {
        if (closing || !channel.isActive()) {
            return;
        }
        sendProbe();
        scheduleProbes(UdpPeer.PROBE_FAST);
        service(false);
    }

    private void scheduleProbes(Duration interval) {
        cancelProbes();
        long nanos = interval.toNanos();
        probeTimer = loop.scheduleAtFixedRate(this::sendProbe, nanos, nanos, TimeUnit.NANOSECONDS);
    }

    private void cancelProbes() {
        if (probeTimer != null) {
            probeTimer.cancel(false);
            probeTimer = null;
        }
    }

    /** Event loop, from the socket's close future: nothing fires afterwards. */
    private void closedOnLoop() {
        closing = true;
        cancelProbes();
        if (pollTimer != null) {
            pollTimer.cancel(false);
            pollTimer = null;
        }
    }

    /**
     * Event loop: polls the receiver (sending its {@code ACK}) when {@code force} or a deadline is due, then arms the
     * one-shot poll timer for its next deadline unless an earlier one is armed already (a later deadline is checked
     * again when that one fires). If the receiver throws, a retry is armed {@value #RETRY_NANOS} ns later; the
     * {@value #MAX_CONSECUTIVE_FAILURES}th failure in a row ends the session.
     */
    private void service(boolean force) {
        if (closing) {
            return;
        }
        long now = System.nanoTime();
        long deadline;
        try {
            faults.beforePoll();
            if (force || receiver.nextDeadlineNanos(now) - now <= 0) {
                receiver.poll(now).forEach(this::send); // also expires incomplete rectangles
            }
            deadline = receiver.nextDeadlineNanos(now);
            pollFailures = 0;
        } catch (RuntimeException e) {
            if (failedAgain(++pollFailures, "Polling the UDP image receiver failed", e)) {
                return;
            }
            deadline = now + RETRY_NANOS;
        }
        if (pollTimer != null && pollAtNanos - deadline <= 0) {
            return;
        }
        if (pollTimer != null) {
            pollTimer.cancel(false);
        }
        pollAtNanos = deadline;
        pollTimer = loop.schedule(this::pollTimerFired, Math.max(0, deadline - now), TimeUnit.NANOSECONDS);
    }

    private void pollTimerFired() {
        pollTimer = null;
        service(true);
    }

    /**
     * Event loop: logs failure number {@code count} of a streak (the first as an ERROR, the rest at DEBUG); at
     * {@link #MAX_CONSECUTIVE_FAILURES} closes the link and runs {@code onFailure} once.
     *
     * @return whether the link was failed
     */
    private boolean failedAgain(int count, String what, RuntimeException e) {
        if (count < MAX_CONSECUTIVE_FAILURES) {
            LOG.log(count == 1 ? Level.ERROR : Level.DEBUG, what + " (retrying)", e);
            return false;
        }
        LOG.log(Level.ERROR, what + " " + count + " times in a row; ending the session", e);
        if (!failed) {
            failed = true;
            close(); // on the event loop: does not wait; nothing is handled or sent afterwards
            if (pollTimer != null) {
                pollTimer.cancel(false);
                pollTimer = null;
            }
            try {
                onFailure.run();
            } catch (RuntimeException ex) {
                LOG.log(Level.ERROR, "UDP failure callback failed", ex);
            }
        }
        return true;
    }

    private void sendProbe() {
        if (closing) {
            return;
        }
        try {
            send(peer.probe(System.nanoTime()));
        } catch (RuntimeException e) { // a throw would silently cancel the periodic task
            LOG.log(Level.WARNING, "Sending a UDP probe failed", e);
        }
    }

    /**
     * Event loop: sends {@code wire} (signed by {@link #peer}) to the agent. Always a {@link DatagramPacket} with its
     * recipient, so the {@link ImpairedDatagramHandler} sees it (it passes other message types through).
     */
    private void send(byte[] wire) {
        channel.writeAndFlush(new DatagramPacket(Unpooled.wrappedBuffer(wire), agent)).addListener(f -> {
            if (!f.isSuccess()) {
                LOG.log(Level.DEBUG, "UDP send to {0} failed: {1}", agent, f.cause());
            }
        });
    }

    /** Event loop: one inbound datagram; its buffer is released by {@link Receiver}. */
    private void receive(DatagramPacket datagram) {
        if (closing) {
            return;
        }
        Optional<UdpPacket> opened = peer.open(datagram.content().nioBuffer());
        if (opened.isEmpty()) {
            return;
        }
        UdpPacket p = opened.get();
        try {
            faults.beforePacket();
            switch (p.type()) {
                case PROBE -> {
                    peer.answer(p).ifPresent(this::send);
                    receiver.onPacket(p, System.nanoTime()); // a probe asks for an ACK too
                }
                // Empty unless it echoes one of our probes.
                case PROBE_REPLY -> peer.rttOf(p, System.nanoTime()).ifPresent(this::roundTrip);
                case TILE -> receiver.onPacket(p, System.nanoTime()).ifPresent(this::deliver);
                case ACK -> {
                    // The agent never acknowledges: nothing to do.
                }
            }
            packetFailures = 0;
        } catch (RuntimeException e) {
            if (failedAgain(++packetFailures, "Handling a UDP " + p.type() + " failed", e)) {
                return;
            }
        }
        service(false);
    }

    private void deliver(CompleteRect rect) {
        try {
            rects.accept(rect);
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "UDP rectangle sink failed, dropping " + rect, e);
            receiver.dropped(rect.rectId());
        }
    }

    private void roundTrip(long rttNanos) {
        lastRttNanos = rttNanos;
        if (!established) {
            established = true;
            scheduleProbes(UdpPeer.PROBE_SLOW);
        }
    }

    /** Releases every datagram ({@link SimpleChannelInboundHandler}); per-packet errors never close the socket. */
    private static final class Receiver extends SimpleChannelInboundHandler<DatagramPacket> {
        volatile ViewerUdpLink link;

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, DatagramPacket msg) {
            ViewerUdpLink l = link;
            if (l == null) {
                return; // raced the end of open(): nothing can be expected yet
            }
            try {
                l.receive(msg);
            } catch (RuntimeException ex) {
                LOG.log(Level.WARNING, "Handling a UDP datagram failed", ex);
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            // E.g. ICMP port unreachable surfacing as a read error on the connected socket: keep going.
            LOG.log(Level.DEBUG, "UDP socket error (ignored): {0}", cause.toString());
        }
    }
}

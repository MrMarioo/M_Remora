package pl.mtech.mremora.agent;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoop;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.socket.DatagramChannel;
import io.netty.channel.socket.DatagramPacket;
import io.netty.channel.socket.nio.NioDatagramChannel;
import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import pl.mtech.mremora.protocol.netty.ImpairedDatagramHandler;
import pl.mtech.mremora.protocol.netty.LinkImpairment;
import pl.mtech.mremora.protocol.udp.UdpPacket;

/**
 * The agent's single UDP socket, shared by all sessions of the process; at most one {@link UdpSessionLink} is
 * attached at a time. Inbound datagrams are checked in the order of the plan (codec: length, magic, direction,
 * session, type, MAC; then the link's replay window) and only then handed to the link; anything else is dropped
 * silently. No invalid datagram and no error handling one closes the socket, nor does a
 * {@link java.net.SocketException} on read (e.g. ICMP port unreachable). Any other read error makes Netty close the
 * socket for good; the owner learns of that through {@link #onUnexpectedClose} (the agent then stops offering v3).
 *
 * <p>The attached link is touched only on the channel's event loop.
 */
final class UdpEndpoint implements AutoCloseable {

    private static final Logger LOG = System.getLogger(UdpEndpoint.class.getName());
    private static final Duration CLOSE_WAIT = Duration.ofSeconds(5);

    private final Channel channel;
    /** {@code null} without an impairment. */
    private final ImpairedDatagramHandler impairment;
    private UdpSessionLink attached;
    /** Datagrams handed to the socket by {@link #send}; written on the event loop only, read anywhere. */
    private volatile long sent;
    /** Set by {@link #close()} before the socket closes: tells an expected close from an unexpected one. */
    private volatile boolean closing;

    private UdpEndpoint(Channel channel, ImpairedDatagramHandler impairment) {
        this.channel = channel;
        this.impairment = impairment;
    }

    /**
     * Binds a UDP socket on all interfaces, its I/O on {@code group}.
     *
     * @param port {@code 0} for any free port
     * @return empty (and a {@code WARNING} logged) when the bind fails
     */
    static Optional<UdpEndpoint> bind(EventLoopGroup group, int port, LinkImpairment impairment) {
        Objects.requireNonNull(group, "group");
        Objects.requireNonNull(impairment, "impairment");
        Receiver receiver = new Receiver();
        ImpairedDatagramHandler impairer =
                impairment.equals(LinkImpairment.NONE) ? null : new ImpairedDatagramHandler(impairment);
        try {
            Channel ch = new Bootstrap()
                    .group(group)
                    .channel(NioDatagramChannel.class)
                    .handler(new ChannelInitializer<DatagramChannel>() {
                        @Override
                        protected void initChannel(DatagramChannel c) {
                            if (impairer != null) {
                                c.pipeline().addLast("impairment", impairer);
                            }
                            c.pipeline().addLast("udp", receiver);
                        }
                    })
                    .bind(port)
                    .syncUninterruptibly()
                    .channel();
            UdpEndpoint endpoint = new UdpEndpoint(ch, impairer);
            receiver.endpoint = endpoint;
            return Optional.of(endpoint);
        } catch (Exception e) { // Netty rethrows checked causes (e.g. BindException) unchecked
            LOG.log(Level.WARNING, "Cannot bind UDP port " + port + ", continuing without UDP: " + e);
            return Optional.empty();
        }
    }

    /** Datagrams the {@link LinkImpairment} dropped so far, both directions; {@code 0} without one. Any thread. */
    long impairmentDrops() {
        return impairment == null ? 0 : impairment.dropped();
    }

    /**
     * Datagrams the attached links sent so far ({@code TILE}, {@code PROBE}, {@code PROBE_REPLY}), counted before the
     * {@link LinkImpairment}. Any thread.
     */
    long datagramsSent() {
        return sent;
    }

    int port() {
        return ((InetSocketAddress) channel.localAddress()).getPort();
    }

    EventLoop eventLoop() {
        return channel.eventLoop();
    }

    /**
     * Runs {@code action} (on the event loop) when the socket closes other than through {@link #close()}, e.g. after
     * an unexpected read error. At once if that already happened.
     */
    void onUnexpectedClose(Runnable action) {
        Objects.requireNonNull(action, "action");
        channel.closeFuture().addListener(f -> {
            if (!closing) {
                action.run();
            }
        });
    }

    /** Tests: closes the socket as a fatal read error would, bypassing {@link #close()}. */
    void closeSocketUnexpectedly() {
        channel.close().awaitUninterruptibly(CLOSE_WAIT.toMillis());
    }

    /**
     * Makes {@code link} the session receiving datagrams. Any thread (hops to the loop and waits).
     *
     * @throws IllegalStateException when another link is attached
     */
    void attach(UdpSessionLink link) {
        Objects.requireNonNull(link, "link");
        onLoop(() -> {
            if (attached != null && attached != link) {
                throw new IllegalStateException("another UDP session is attached");
            }
            attached = link;
        });
    }

    /** Detaches {@code link} (if attached) and closes it, cancelling its timers. Any thread. */
    void detach(UdpSessionLink link) {
        Objects.requireNonNull(link, "link");
        onLoop(() -> {
            if (attached == link) {
                attached = null;
            }
            link.close();
        });
    }

    /**
     * Event loop: sends {@code wire}, a datagram signed by {@code from}. Dropped when {@code from} is not the attached
     * link (e.g. a late timer of a detached one).
     */
    void send(UdpSessionLink from, byte[] wire, InetSocketAddress to) {
        if (attached == null || attached != from) {
            LOG.log(Level.DEBUG, "Dropping outbound UDP packet of a session that is not attached");
            return;
        }
        sent++; // event loop only
        channel.writeAndFlush(new DatagramPacket(Unpooled.wrappedBuffer(wire), to)).addListener(f -> {
            if (!f.isSuccess()) {
                LOG.log(Level.DEBUG, "UDP send to {0} failed: {1}", to, f.cause());
            }
        });
    }

    /** Closes the attached link and the socket. Idempotent; on the event loop it does not wait. */
    @Override
    public void close() {
        closing = true;
        if (eventLoop().inEventLoop()) {
            closeAttached();
            channel.close();
            return;
        }
        if (!eventLoop().isShuttingDown()) {
            eventLoop().submit(this::closeAttached).syncUninterruptibly();
        }
        channel.close().awaitUninterruptibly(CLOSE_WAIT.toMillis());
    }

    private void closeAttached() {
        if (attached != null) {
            attached.close();
            attached = null;
        }
    }

    private void onLoop(Runnable task) {
        if (eventLoop().inEventLoop()) {
            task.run();
        } else {
            eventLoop().submit(task).syncUninterruptibly();
        }
    }

    /** Event loop: one inbound datagram. {@link SimpleChannelInboundHandler} releases it on every path. */
    private void receive(DatagramPacket datagram) {
        UdpSessionLink link = attached;
        if (link == null) {
            return;
        }
        Optional<UdpPacket> opened = link.open(datagram.content().nioBuffer());
        if (opened.isPresent()) {
            link.received(opened.get(), datagram.sender());
        }
    }

    private static final class Receiver extends SimpleChannelInboundHandler<DatagramPacket> {
        volatile UdpEndpoint endpoint;

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, DatagramPacket msg) {
            UdpEndpoint e = endpoint;
            if (e == null) {
                return; // datagram raced the end of bind(): no session can be attached yet
            }
            try {
                e.receive(msg);
            } catch (RuntimeException ex) { // never let one packet hurt the shared socket
                LOG.log(Level.WARNING, "Handling a UDP datagram failed", ex);
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            // Shared socket: log and keep it open. I/O errors are expected (e.g. ICMP port unreachable surfacing as
            // a read error); anything else is a bug worth a stack trace. A fatal read error closes the socket in
            // Netty whatever we do here - see onUnexpectedClose.
            if (cause instanceof IOException) {
                LOG.log(Level.DEBUG, "UDP socket error (ignored): {0}", cause.toString());
            } else {
                LOG.log(Level.WARNING, "Unexpected UDP socket error (ignored)", cause);
            }
        }
    }
}

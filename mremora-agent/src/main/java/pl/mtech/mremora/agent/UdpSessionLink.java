package pl.mtech.mremora.agent;

import io.netty.channel.EventLoop;
import io.netty.util.concurrent.ScheduledFuture;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import pl.mtech.mremora.protocol.TransportState;
import pl.mtech.mremora.protocol.udp.UdpPacket;
import pl.mtech.mremora.protocol.udp.UdpPacket.Direction;
import pl.mtech.mremora.protocol.udp.UdpPeer;

/**
 * The UDP side of one v3 session on the agent: answers the viewer's probes, learns the viewer's address from its
 * newest authenticated packet, probes that address (first probe at once, then every {@link UdpPeer#PROBE_FAST}
 * until the decision, then every {@link UdpPeer#PROBE_SLOW} as NAT keepalive) and decides UDP vs TCP within the
 * startup timeout.
 *
 * <p>Once the session streams its picture over UDP, a {@link UdpImageChannel} is attached ({@link #attachImage}): it
 * seals with this link's {@link #peer()} (one packet numbering for probes and fragments), gets the viewer's {@code ACK}
 * and {@code PROBE_REPLY} packets, and is closed together with the link. Without one (TCP picture, or before the
 * decision) {@code ACK}s are ignored.
 *
 * <p>All state lives on the endpoint's event loop. {@link #start()} and {@link #close()} may be called from any
 * thread (they hop to the loop and wait); everything else must be called on the loop. {@code onDecided} is called
 * exactly once on the loop, unless the link is closed first - after {@link #close()} nothing fires.
 */
final class UdpSessionLink {

    static final Duration DECISION_TICK = Duration.ofMillis(50);

    private static final Logger LOG = System.getLogger(UdpSessionLink.class.getName());

    private final UdpEndpoint endpoint;
    private final EventLoop loop;
    private final byte[] key;
    private final LongSupplier nanoTime;
    private final Duration startupTimeout;
    private final Consumer<TransportState.Mode> onDecided;
    private final UdpPeer peer;

    // Event-loop state.
    private boolean started;
    private boolean closed;
    private boolean decided;
    private UdpStartup startup;
    private ScheduledFuture<?> decisionTick;
    private ScheduledFuture<?> probeTimer;
    private InetSocketAddress viewer;
    /** Highest packet number accepted from the viewer; {@code -1} before the first. */
    private long highestReceived = -1;
    private long lastRttNanos = -1;
    /** The picture over UDP; {@code null} unless the session decided UDP. */
    private UdpImageChannel image;

    /**
     * @param nanoTime monotonic clock of the startup timeout ({@code System::nanoTime}; a fake in tests). Probe
     *                 timestamps and RTTs always use {@link System#nanoTime()}.
     */
    UdpSessionLink(UdpEndpoint endpoint, int sessionId, byte[] key, LongSupplier nanoTime, Duration startupTimeout,
                   Consumer<TransportState.Mode> onDecided) {
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
        this.loop = endpoint.eventLoop();
        this.key = Objects.requireNonNull(key, "key").clone();
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
        this.startupTimeout = Objects.requireNonNull(startupTimeout, "startupTimeout");
        this.onDecided = Objects.requireNonNull(onDecided, "onDecided");
        this.peer = new UdpPeer(Direction.AGENT, sessionId, this.key);
    }

    /** The endpoint's event loop, which owns all of the link's state. */
    EventLoop eventLoop() {
        return loop;
    }

    /** A copy of the session key. */
    byte[] key() {
        return key.clone();
    }

    /**
     * Event loop: the session's peer, which numbers and signs every packet to the viewer. Shared with the image
     * channel, so it must only be used on the loop.
     */
    UdpPeer peer() {
        return peer;
    }

    /**
     * Event loop: hands the viewer's {@code ACK}/{@code PROBE_REPLY} packets to {@code channel} from now on and closes
     * it with the link (at once if the link is already closed).
     *
     * @throws IllegalStateException when another channel is attached
     */
    void attachImage(UdpImageChannel channel) {
        Objects.requireNonNull(channel, "channel");
        if (image != null && image != channel) {
            throw new IllegalStateException("an image channel is already attached");
        }
        image = channel;
        if (closed) {
            channel.close();
        }
    }

    /**
     * Event loop: sends a datagram sealed by {@link #peer()} to the viewer's current address. Dropped after
     * {@link #close()}, before the viewer's first packet, or when this link is no longer attached to the endpoint.
     */
    void sendToViewer(byte[] wire) {
        if (closed || viewer == null) {
            return;
        }
        endpoint.send(this, wire, viewer);
    }

    /** Event loop: the viewer's current address, empty until its first authenticated packet. */
    Optional<InetSocketAddress> viewerAddress() {
        return Optional.ofNullable(viewer);
    }

    /** Event loop: round-trip time of the latest answered agent probe. */
    OptionalLong lastRttNanos() {
        return lastRttNanos < 0 ? OptionalLong.empty() : OptionalLong.of(lastRttNanos);
    }

    /**
     * Event loop: verifies one inbound datagram (signature, viewer direction, replay window); see
     * {@link UdpPeer#open}. Called by the endpoint before {@link #received}.
     */
    Optional<UdpPacket> open(ByteBuffer datagram) {
        return peer.open(datagram);
    }

    /**
     * Event loop, tests: whether a probe timer or the decision tick is still scheduled (not cancelled). After
     * {@link #close()} always {@code false}.
     */
    boolean timersActive() {
        return live(decisionTick) || live(probeTimer);
    }

    /** Event loop, tests: whether the probe timer is scheduled. */
    boolean probing() {
        return live(probeTimer);
    }

    private static boolean live(ScheduledFuture<?> f) {
        return f != null && !f.isDone();
    }

    /** Starts the startup timeout. Idempotent; a no-op after {@link #close()}. */
    void start() {
        if (!loop.inEventLoop()) {
            loop.submit(this::start).syncUninterruptibly();
            return;
        }
        if (started || closed) {
            return;
        }
        started = true;
        startup = new UdpStartup(nanoTime, startupTimeout);
        long tick = DECISION_TICK.toNanos();
        decisionTick = loop.scheduleAtFixedRate(this::checkDecision, tick, tick, TimeUnit.NANOSECONDS);
    }

    /** Cancels all timers; nothing fires afterwards. Idempotent, any thread. */
    void close() {
        if (!loop.inEventLoop()) {
            if (loop.isShuttingDown()) {
                return; // the loop's scheduled tasks die with it
            }
            loop.submit(this::close).syncUninterruptibly();
            return;
        }
        if (closed) {
            return;
        }
        closed = true;
        if (image != null) {
            image.close();
        }
        cancel(decisionTick); // kept (cancelled) rather than nulled, so timersActive() checks the real futures
        cancel(probeTimer);
    }

    /**
     * Event loop, called by the endpoint for a packet that already passed the MAC and the replay window.
     * Ignored before {@link #start()} and after {@link #close()}.
     */
    void received(UdpPacket p, InetSocketAddress sender) {
        if (!started || closed) {
            return;
        }
        if (p.number() > highestReceived) {
            highestReceived = p.number();
            boolean first = viewer == null;
            viewer = sender;
            if (first) {
                startProbing();
            }
        }
        switch (p.type()) {
            case PROBE -> peer.answer(p).ifPresent(reply -> endpoint.send(this, reply, sender));
            // Empty unless it echoes one of our probes: anything else proves nothing about the agent -> viewer leg.
            case PROBE_REPLY -> {
                peer.rttOf(p, System.nanoTime()).ifPresent(this::roundTrip);
                if (image != null) {
                    image.onPacket(p);
                }
            }
            case ACK -> {
                if (image != null) {
                    image.onPacket(p); // otherwise (TCP picture) the viewer's ACKs of our probes are of no use
                }
            }
            default -> {
            }
        }
    }

    private void roundTrip(long rttNanos) {
        lastRttNanos = rttNanos;
        startup.roundTripSucceeded();
        checkDecision();
    }

    private void startProbing() {
        sendProbe();
        scheduleProbes(decided ? UdpPeer.PROBE_SLOW : UdpPeer.PROBE_FAST);
    }

    private void scheduleProbes(Duration interval) {
        cancel(probeTimer);
        long nanos = interval.toNanos();
        probeTimer = loop.scheduleAtFixedRate(this::sendProbe, nanos, nanos, TimeUnit.NANOSECONDS);
    }

    private void sendProbe() {
        if (closed || viewer == null) {
            return;
        }
        try {
            endpoint.send(this, peer.probe(System.nanoTime()), viewer);
        } catch (RuntimeException e) { // a throw would silently cancel the periodic task
            LOG.log(Level.WARNING, "Sending a UDP probe failed", e);
        }
    }

    private void checkDecision() {
        if (decided || closed || startup == null) {
            return;
        }
        try {
            Optional<TransportState.Mode> outcome = startup.outcome();
            if (outcome.isEmpty()) {
                return;
            }
            decided = true;
            cancel(decisionTick);
            decisionTick = null;
            if (probeTimer != null) {
                scheduleProbes(UdpPeer.PROBE_SLOW);
            }
            onDecided.accept(outcome.get());
        } catch (RuntimeException e) { // a throw would silently cancel the periodic task
            LOG.log(Level.WARNING, "UDP startup decision failed", e);
        }
    }

    private static void cancel(ScheduledFuture<?> f) {
        if (f != null) {
            f.cancel(false);
        }
    }
}

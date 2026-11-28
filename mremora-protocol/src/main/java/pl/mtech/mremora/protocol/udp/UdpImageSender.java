package pl.mtech.mremora.protocol.udp;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.TreeSet;
import pl.mtech.mremora.protocol.udp.RangeSet.Range;
import pl.mtech.mremora.protocol.udp.UdpPacket.Type;

/**
 * Agent side of the image stream over UDP: the facade over {@link TileDelivery}, {@link RectFragmenter},
 * {@link SendPacer}, {@link LossDetector} and {@link RttEstimator}. The caller owns the socket and the timer: it
 * submits encoded rectangles, sends whatever {@link #poll} returns, calls {@code poll} again at
 * {@link #nextDeadlineNanos}, and passes every opened packet of the viewer to {@link #onPacket}.
 *
 * <ul>
 *   <li>Fragments are queued unsealed; a packet is numbered and signed ({@link UdpPeer#seal}) only when it leaves the
 *       pacer, so packet numbers follow the send order (a PTO probe sent between two fragments gets a number between
 *       theirs).</li>
 *   <li>A packet is registered as <i>pacer-limited</i> while the queue is backlogged: from the moment the pacer
 *       refused the head of the queue until the queue drains. Only those packets let the rate grow, so a mostly idle
 *       desktop whose updates fit the token bucket never inflates it.</li>
 *   <li>Within one ACK the finished and dropped rectangles are applied before the packet losses, so a rectangle the
 *       viewer already applied is not forced again.</li>
 *   <li>The rate reacts only to packet losses, never to a confirm timeout or a dropped rectangle.</li>
 *   <li>Queued fragments of a rectangle that has already ended (lost, dropped) are discarded instead of sent.</li>
 *   <li>Every accepted submit must carry a {@code captureSeq} strictly greater than any earlier one of the session
 *       (across epochs): the viewer applies a tile only from a newer capture, so a resend under an old number would
 *       be skipped and the tile stay stale.</li>
 *   <li>While captures are unresolved but nothing ack-eliciting is in flight (the viewer still has to apply them),
 *       a <i>resolve probe</i> is sent every {@link #RESOLVE_PROBE_MIN_NANOS} or PTO, whichever is longer: its ACK
 *       carries the finished/dropped rects again, so a lost ACK cannot keep the capture gate closed.</li>
 * </ul>
 * Single-threaded; time only from {@code nowNanos}, compared overflow-safe.
 */
public final class UdpImageSender {

    /** Smallest network budget of the capture gate. */
    public static final long MIN_BUDGET_BYTES = 64 * 1024;
    /** Viewer side of the capture gate: at most this many captures with unresolved rectangles. */
    public static final int MAX_UNRESOLVED_CAPTURES = 2;
    /** Shortest interval of resolve probes (see the class description). */
    static final long RESOLVE_PROBE_MIN_NANOS = 200_000_000L;

    /** How often {@link #nextDeadlineNanos} wakes the caller to check confirm timeouts while rects are outstanding. */
    static final long CONFIRM_CHECK_NANOS = 100_000_000L;
    /** Wake-up interval when nothing at all is pending. */
    static final long IDLE_NANOS = 1_000_000_000L;

    private static final long NANOS_PER_MS = 1_000_000L;
    private static final double NANOS_PER_SECOND = 1_000_000_000d;

    /**
     * One encoded rectangle of a capture, tile-aligned (as planned by the encoder).
     *
     * @param encoding {@link TileFragment#SOLID}, {@link TileFragment#LOSSLESS} or {@link TileFragment#JPEG}
     * @param data     encoded bytes, not copied (callers must not modify them afterwards)
     */
    public record OutgoingRect(int x, int y, int width, int height, int encoding, byte[] data) {
        public OutgoingRect {
            Objects.requireNonNull(data, "data");
        }
    }

    private record Queued(long rectId, byte[] payload) {}

    private record InFlight(long rectId, long sentNanos) {}

    private static final long NO_RECT = 0;
    private static final Logger LOG = System.getLogger(UdpImageSender.class.getName());

    private final UdpPeer peer;
    private final RttEstimator rtt = new RttEstimator();
    private final LossDetector loss = new LossDetector(rtt);
    private final SendPacer pacer;
    private final TileDelivery delivery = new TileDelivery();
    private final ArrayDeque<Queued> queue = new ArrayDeque<>();
    /** Packets in flight (acked or lost ones are removed), with their rectangle ({@link #NO_RECT} for a probe). */
    private final Map<Long, InFlight> inFlight = new HashMap<>();
    /** rectIds registered and not yet known to have ended; walked by range when the viewer reports them done. */
    private final TreeSet<Long> live = new TreeSet<>();

    private TileGrid grid;
    private int streamWidth;
    private int streamHeight;
    private boolean backlogged;
    /** Wire bytes of the fragments in {@link #queue}. */
    private long queuedBytes;
    private long lastElicitingSentNanos;
    private long confirmTimeouts;
    private long lostPackets;
    private long droppedReports;
    /** Highest {@code captureSeq} of an accepted submit, -1 before the first. */
    private long lastCaptureSeq = -1;
    private long tilePacketsSent;
    private long rectsSubmitted;
    private long fragmentsQueued;
    private int maxFragmentsPerRect;
    private long tilesForced;

    public UdpImageSender(UdpPeer peer, long nowNanos) {
        this.peer = Objects.requireNonNull(peer, "peer");
        this.pacer = new SendPacer(nowNanos);
        this.lastElicitingSentNanos = nowNanos;
    }

    /**
     * Starts {@code epoch} for a stream of the given size: everything of the previous epoch (queued fragments,
     * unconfirmed rectangles, forced tiles) is forgotten. Packets already in flight keep counting for loss detection
     * and the rate.
     *
     * @throws IllegalArgumentException if the epoch or the stream size does not fit the wire format (u16)
     */
    public void newEpoch(int epoch, int streamWidth, int streamHeight) {
        if (epoch < 0 || epoch > 0xFFFF || streamWidth < 1 || streamWidth > 0xFFFF
                || streamHeight < 1 || streamHeight > 0xFFFF) {
            throw new IllegalArgumentException("epoch %d / stream %dx%d out of the wire range"
                    .formatted(epoch, streamWidth, streamHeight));
        }
        this.grid = new TileGrid(streamWidth, streamHeight);
        this.streamWidth = streamWidth;
        this.streamHeight = streamHeight;
        delivery.newEpoch(epoch, grid);
        queue.clear();
        queuedBytes = 0;
        live.clear();
        backlogged = false;
    }

    /**
     * Registers and queues the rectangles of one capture. A submit for an epoch other than the current one (or before
     * the first {@link #newEpoch}) is ignored: the caller must start the epoch first, since the encoder already
     * counts the ignored tiles as sent.
     *
     * @return {@code false} when the submit was ignored for its epoch
     * @throws IllegalArgumentException if {@code captureSeq} is negative or not greater than that of every earlier
     *                                  accepted submit, or a rectangle lies outside the stream, is empty or larger
     *                                  than {@link TileFragment#MAX_RECT_BYTES}; nothing of the capture is registered
     *                                  then
     */
    public boolean submit(int epoch, long captureSeq, List<OutgoingRect> rects, long nowNanos) {
        if (grid == null || epoch != delivery.epoch()) {
            return false;
        }
        if (captureSeq < 0) {
            throw new IllegalArgumentException("captureSeq must be >= 0: " + captureSeq);
        }
        for (OutgoingRect r : rects) {
            if (r.width() < 1 || r.height() < 1 || r.x() < 0 || r.y() < 0
                    || r.x() + (long) r.width() > streamWidth || r.y() + (long) r.height() > streamHeight) {
                throw new IllegalArgumentException("rect outside the %dx%d stream: %s"
                        .formatted(streamWidth, streamHeight, r));
            }
            if (r.data().length < 1 || r.data().length > TileFragment.MAX_RECT_BYTES) {
                throw new IllegalArgumentException("rect size out of range: " + r.data().length);
            }
            if (r.encoding() < TileFragment.SOLID || r.encoding() > TileFragment.JPEG) {
                throw new IllegalArgumentException("unknown encoding: " + r.encoding());
            }
        }
        if (captureSeq <= lastCaptureSeq) {
            LOG.log(Level.ERROR, "Rejecting capture {0}: not after capture {1} (the viewer would skip it)",
                    captureSeq, lastCaptureSeq);
            throw new IllegalArgumentException("captureSeq " + captureSeq + " not after " + lastCaptureSeq);
        }
        lastCaptureSeq = captureSeq;
        for (OutgoingRect r : rects) {
            BitSet tiles = grid.tilesOf(r.x(), r.y(), r.width(), r.height());
            long rectId = delivery.register(epoch, captureSeq, tiles, r.data().length);
            live.add(rectId);
            CompleteRect whole = new CompleteRect(epoch, streamWidth, streamHeight, captureSeq, rectId,
                    r.x(), r.y(), r.width(), r.height(), r.encoding(), r.data());
            List<TileFragment> fragments = RectFragmenter.split(whole);
            for (TileFragment f : fragments) {
                Queued q = new Queued(rectId, f.encode());
                queue.addLast(q);
                queuedBytes += wireSize(q);
            }
            rectsSubmitted++;
            fragmentsQueued += fragments.size();
            maxFragmentsPerRect = Math.max(maxFragmentsPerRect, fragments.size());
        }
        return true;
    }

    /**
     * Datagrams to send now, in this order: a PTO probe if the probe timeout fired, then queued fragments as far as
     * the pacer allows (each sealed here), then a resolve probe if one is due. Also declares time-threshold losses
     * and confirm timeouts.
     */
    public List<byte[]> poll(long nowNanos) {
        List<byte[]> out = new ArrayList<>();
        OptionalLong lossTime = loss.lossTimeDeadline();
        if (lossTime.isPresent() && nowNanos - lossTime.getAsLong() >= 0) {
            onLost(loss.detectByTime(nowNanos), nowNanos);
        }
        int timedOut = delivery.checkConfirmTimeout(nowNanos);
        if (timedOut > 0) {
            confirmTimeouts += timedOut;
            live.removeIf(id -> !delivery.isLive(id)); // ended by the timeout: no longer worth a range walk
        }
        if (loss.anyAckElicitingInFlight() && nowNanos - loss.ptoDeadline() >= 0) {
            byte[] probe = peer.probe(nowNanos);
            loss.ptoFired();
            register(probe, NO_RECT, false, nowNanos);
            out.add(probe);
        }
        while (!queue.isEmpty()) {
            Queued head = queue.peekFirst();
            if (!delivery.isLive(head.rectId())) {
                queue.pollFirst(); // its rect already ended: the viewer cannot use the rest
                queuedBytes -= wireSize(head);
                continue;
            }
            if (pacer.nanosUntilAllowed(wireSize(head), nowNanos) > 0) {
                backlogged = true;
                break;
            }
            queue.pollFirst();
            queuedBytes -= wireSize(head);
            byte[] datagram = peer.seal(Type.TILE, head.payload());
            long number = register(datagram, head.rectId(), backlogged, nowNanos);
            delivery.packetSent(head.rectId(), number);
            tilePacketsSent++;
            out.add(datagram);
        }
        if (queue.isEmpty()) {
            backlogged = false;
            if (resolveProbeDue() && nowNanos - (lastElicitingSentNanos + resolveProbeInterval()) >= 0) {
                byte[] probe = peer.probe(nowNanos);
                register(probe, NO_RECT, false, nowNanos);
                out.add(probe);
            }
        }
        return out;
    }

    /**
     * When {@link #poll} has something to do next: the pacer releasing the queue head, a loss-time threshold, the
     * PTO, a resolve probe, a confirm-timeout check ({@value #CONFIRM_CHECK_NANOS} ns granularity) or, with
     * nothing pending, an idle wake-up a second later.
     */
    public long nextDeadlineNanos(long nowNanos) {
        long delta = delivery.unconfirmedBytes() > 0 || !live.isEmpty() ? CONFIRM_CHECK_NANOS : IDLE_NANOS;
        if (!queue.isEmpty()) {
            Queued head = queue.peekFirst();
            delta = Math.min(delta, pacer.nanosUntilAllowed(wireSize(head), nowNanos));
        } else if (resolveProbeDue()) {
            delta = Math.min(delta, Math.max(0, lastElicitingSentNanos + resolveProbeInterval() - nowNanos));
        }
        OptionalLong lossTime = loss.lossTimeDeadline();
        if (lossTime.isPresent()) {
            delta = Math.min(delta, Math.max(0, lossTime.getAsLong() - nowNanos));
        }
        if (loss.anyAckElicitingInFlight()) {
            delta = Math.min(delta, Math.max(0, loss.ptoDeadline() - nowNanos));
        }
        return nowNanos + delta;
    }

    /** Handles an opened packet of the viewer: {@code ACK} and {@code PROBE_REPLY}; anything else is ignored. */
    public void onPacket(UdpPacket p, long nowNanos) {
        switch (p.type()) {
            case ACK -> AckFrame.decode(p.payload()).ifPresent(f -> onAck(f, nowNanos));
            case PROBE_REPLY -> {
                OptionalLong sample = peer.rttOf(p, nowNanos);
                if (sample.isPresent()) {
                    rtt.sample(Math.max(sample.getAsLong(), 0), 0);
                }
            }
            default -> {
            }
        }
    }

    /**
     * Returns the forced tiles (of a lost, dropped or timed-out rectangle) and clears them. Take them only when the
     * capture that will carry them is actually encoded and submitted right away: taken tiles are not forced again.
     */
    public TileDelivery.Forced takeForced() {
        TileDelivery.Forced f = delivery.takeForced();
        tilesForced += f.tiles().cardinality();
        return f;
    }

    /**
     * Capture gate, both conditions required:
     * <ul>
     *   <li><b>network:</b> bytes queued in the pacer plus bytes sent and not acknowledged are below
     *       {@code max(64 KiB, rate / 8 × 2·sRTT)}, or nothing is queued or in flight at all (a full frame larger
     *       than the budget must be able to go out);</li>
     *   <li><b>viewer:</b> fewer than {@link #MAX_UNRESOLVED_CAPTURES} captures still have rectangles that are
     *       neither done nor lost/dropped/timed out, so a viewer that applies slowly slows the captures down.</li>
     * </ul>
     */
    public boolean admitsCapture() {
        if (delivery.unresolvedCaptures() >= MAX_UNRESOLVED_CAPTURES) {
            return false;
        }
        long network = networkBytes();
        if (network == 0) {
            return true;
        }
        double rttSeconds = rtt.hasSample() ? rtt.smoothed() / NANOS_PER_SECOND : 0;
        double budget = Math.max(MIN_BUDGET_BYTES, pacer.rateBitsPerSecond() / 8d * 2 * rttSeconds);
        return network < budget;
    }

    /** Bytes the network side of the gate counts: queued in the pacer plus sent and not acknowledged. */
    public long networkBytes() {
        return queuedBytes + loss.bytesInFlight();
    }

    /** Current pacing rate in bits per second. */
    public long rateBitsPerSecond() {
        return pacer.rateBitsPerSecond();
    }

    /** Smoothed RTT; empty before the first sample. */
    public OptionalLong smoothedRttNanos() {
        return rtt.hasSample() ? OptionalLong.of(rtt.smoothed()) : OptionalLong.empty();
    }

    /** Current epoch, -1 before the first {@link #newEpoch}. */
    public int epoch() {
        return delivery.epoch();
    }

    /** Bytes of rectangles queued or sent and not yet done or ended otherwise. */
    public long outstandingBytes() {
        return delivery.unconfirmedBytes();
    }

    /** Whether forced tiles wait for {@link #takeForced()}; tests (does not take them). */
    boolean hasForcedTiles() {
        return delivery.hasForced();
    }

    /** Fragments waiting in the pacer queue (including ones of ended rectangles not discarded yet). */
    int queuedPackets() {
        return queue.size();
    }

    /** Rectangles that ended by confirm timeout so far (a resend without a reported cause). */
    long confirmTimeouts() {
        return confirmTimeouts;
    }

    /** Size of the facade's set of live rectIds (must track {@link TileDelivery#liveRects()}). */
    int trackedLiveRects() {
        return live.size();
    }

    /** Live rectangles according to {@link TileDelivery}. */
    int deliveryLiveRects() {
        return delivery.liveRects();
    }

    /** Packets declared lost so far (fragments and probes). */
    public long lostPackets() {
        return lostPackets;
    }

    /** {@code TILE} packets sent so far. */
    public long tilePacketsSent() {
        return tilePacketsSent;
    }

    /** Rectangles accepted by {@link #submit} so far. */
    public long rectsSubmitted() {
        return rectsSubmitted;
    }

    /** Fragments those rectangles were split into. */
    public long fragmentsQueued() {
        return fragmentsQueued;
    }

    /** Most fragments of any one rectangle so far, {@code 0} before the first. */
    public int maxFragmentsPerRect() {
        return maxFragmentsPerRect;
    }

    /** Forced tiles handed out by {@link #takeForced} so far. */
    public long tilesForced() {
        return tilesForced;
    }

    /** Dropped reports of the viewer that ended a rectangle so far. */
    long droppedReports() {
        return droppedReports;
    }

    private static int wireSize(Queued q) {
        return UdpPacketCodec.HEADER + q.payload().length + UdpPacketCodec.MAC_LENGTH;
    }

    private boolean resolveProbeDue() {
        return delivery.unresolvedCaptures() > 0 && !loss.anyAckElicitingInFlight();
    }

    private long resolveProbeInterval() {
        return Math.max(RESOLVE_PROBE_MIN_NANOS, rtt.pto());
    }

    /** Registers a sealed datagram (the number of the last {@link UdpPeer#seal}) as sent; returns its number. */
    private long register(byte[] datagram, long rectId, boolean pacerLimited, long nowNanos) {
        long number = peer.lastSealedNumber();
        lastElicitingSentNanos = nowNanos;
        loss.sent(number, nowNanos, datagram.length, true, pacerLimited);
        pacer.consumed(datagram.length, nowNanos);
        inFlight.put(number, new InFlight(rectId, nowNanos));
        return number;
    }

    private void onAck(AckFrame f, long nowNanos) {
        LossDetector.AckOutcome outcome = loss.onAck(f.packets(), f.ackDelayMs() * NANOS_PER_MS, nowNanos);
        for (long n : outcome.acked()) {
            inFlight.remove(n);
        }
        delivery.packetsAcked(outcome.acked(), nowNanos);
        boolean progress = false;
        for (Range r : f.doneRects()) {
            List<Long> ids = new ArrayList<>(live.subSet(r.from(), true, r.to(), true));
            for (long id : ids) {
                progress |= delivery.done(id);
                live.remove(id);
            }
        }
        for (long id : f.droppedRects()) {
            if (delivery.dropped(id)) {
                progress = true;
                droppedReports++;
            }
            live.remove(id);
        }
        if (progress) {
            delivery.viewerProgress(nowNanos);
        }
        onLost(outcome.lost(), nowNanos);
        if (rtt.hasSample()) {
            pacer.onAcked(outcome.ackedPacerLimitedBytes(), rtt.smoothed(), nowNanos);
        }
    }

    private void onLost(List<Long> lost, long nowNanos) {
        for (long n : lost) {
            InFlight s = inFlight.remove(n);
            delivery.packetLost(n);
            lostPackets++;
            if (s == null) {
                continue;
            }
            if (s.rectId() != NO_RECT) {
                live.remove(s.rectId());
            }
            pacer.onLoss(s.sentNanos(), nowNanos);
        }
    }
}

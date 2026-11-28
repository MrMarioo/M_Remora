package pl.mtech.mremora.protocol.udp;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Random;
import java.util.Set;
import java.util.function.Consumer;
import pl.mtech.mremora.protocol.udp.UdpImageSender.OutgoingRect;
import pl.mtech.mremora.protocol.udp.UdpPacket.Direction;
import pl.mtech.mremora.protocol.udp.UdpPacket.Type;

/**
 * Deterministic simulation of an agent and a viewer talking over a lossy link, on a virtual clock (an event queue,
 * nothing waits for real time). The agent's "screen" is an array of tile versions; a rectangle carries the
 * {@code (tile, version)} pairs of its tiles as its {@code data} (padded to a configured encoded size). The viewer
 * applies rectangles through {@link TileVersions} (immediately or with a serial apply delay) and reports them with
 * {@link UdpImageReceiver#applied}. Real {@link UdpPeer}s sign, number and verify every datagram, so duplicates are
 * eaten by the replay window as in production.
 *
 * <p>Link: one-way delay {@code rtt / 2}, seeded random loss, duplicates and jitter (reordering), and on the agent →
 * viewer direction an optional bandwidth limit with a {@value #ROUTER_QUEUE} byte router queue (overflow is lost).
 */
final class SimulatedLink {

    static final long MS = 1_000_000L;
    static final long SECOND = 1_000 * MS;
    static final int ROUTER_QUEUE = 64 * 1024;
    static final long TICK = SECOND / 30;
    /** Virtual time starts close to the wrap of {@code long}: every comparison must be overflow-safe. */
    static final long T0 = Long.MAX_VALUE - 3 * SECOND;

    private static final int SESSION = 0x51A1_0001;
    private static final long MAX_EVENTS = 20_000_000L;

    /** Scenario parameters; every field has a harmless default. Times are relative to the start. */
    static final class Config {
        long seed = 1;
        long rttNanos = 20 * MS;
        double loss;
        double duplicates;
        long jitterNanos;
        /** Agent → viewer bandwidth in bits per second, 0 = unlimited. */
        long bandwidthBps;
        /** Loss of viewer → agent datagrams only (ACKs), on top of {@link #loss}. */
        double ackLoss;
        /** Serial apply time per rectangle on the viewer, 0 = immediate. */
        long applyNanos;
        /**
         * Whether {@link #applyNanos} is serial (a busy decoder: rects wait for each other) or a plain per-rect
         * latency.
         */
        boolean applySerial = true;
        int width = 1024;
        int height = 768;
        int bytesPerTile = 1000;
        int maxMergeTiles = 4;
        /** Random tiles changed per tick until {@link #changesUntil}. */
        int changesPerTick = 4;
        long changesUntil = 3 * SECOND;
        /** When the stream changes size (a new epoch), negative = never. */
        long resizeAt = -1;
        int resizeWidth = 800;
        int resizeHeight = 600;
        /** One last change of a single tile at this time (negative = none), e.g. to lose its last packet. */
        long finalChangeAt = -1;
        /** Drop the very last packet of the final change (once). */
        boolean loseFinalTail;
        long runFor = 30 * SECOND;
        /** The viewer drops the first rect of this capture (as if it failed to decode), negative = never. */
        long dropRectOfCapture = -1;
        /** Every ACK reporting that dropped rect is lost on the way back. */
        boolean loseDroppedReport;
        /**
         * Granularity of the agent's timer (e.g. 15.6 ms on Windows), 0 = exact. Timer deadlines are rounded up to a
         * multiple of it (relative to the start); pumps driven by an ACK or a capture stay immediate unless
         * {@link #roundEveryPump}.
         */
        long timerGranularityNanos;
        /** With {@link #timerGranularityNanos}: every agent pump waits for the next timer boundary. */
        boolean roundEveryPump;

        Config with(Consumer<Config> c) {
            c.accept(this);
            return this;
        }
    }

    /** What happened; the end-state comparison is in {@link #converged()}. */
    static final class Stats {
        long agentSentBytes;
        long forcedTilesTaken;
        long resentTileVersions;
        long tailDrops;
        long overflowDrops;
        long randomDrops;
        /** Random drops of viewer → agent datagrams (ACKs, probe replies). */
        long ackDrops;
        long rejectedDatagrams;
        long maxAssemblyNanos;
        /** Rects assembled but not yet applied by the viewer, at most (and their bytes). */
        long maxApplyBacklogRects;
        long maxApplyBacklogBytes;
        /** Longest time the capture gate stayed closed. */
        long maxGateClosedNanos;
        long droppedReportsLost;
        /** Time of the last change of the agent's screen (relative). */
        long lastChangeAt;
        /** First time (relative) after the last change at which everything was converged and idle; -1 = never. */
        long convergedAt = -1;
        /** Datagrams the agent sent (fragments and probes). */
        long agentDatagrams;
        /** Time (relative) of the agent's last datagram; -1 = none. */
        long lastAgentSendAt = -1;
        /** Longest time from a capture's submit to the send of one of its fragments. */
        long maxQueueLatencyNanos;
        /** {@code [time relative, agent bytes sent so far]} sampled every tick. */
        final List<long[]> sentSamples = new ArrayList<>();
        /** {@code [time relative, pacer rate]} sampled every tick. */
        final List<long[]> rateSamples = new ArrayList<>();

        /** Average emitted bits per second between two relative times. */
        double sentBps(long from, long to) {
            long a = bytesAt(from);
            long b = bytesAt(to);
            return (b - a) * 8d * SECOND / (to - from);
        }

        double meanRate(long from, long to) {
            return rateSamples.stream().filter(s -> s[0] >= from && s[0] < to).mapToLong(s -> s[1]).average()
                    .orElse(Double.NaN);
        }

        private long bytesAt(long t) {
            long last = 0;
            for (long[] s : sentSamples) {
                if (s[0] > t) {
                    break;
                }
                last = s[1];
            }
            return last;
        }
    }

    private record Event(long time, long seq, Runnable action) {}

    /** One direction of the link. */
    private final class Path {
        final long delay;
        final double loss;
        final double duplicates;
        final long jitter;
        final long bandwidthBps;
        final Random random;
        final boolean towardsAgent;
        long linkFreeAt;

        Path(boolean towardsAgent, long delay, double loss, double duplicates, long jitter, long bandwidthBps, long seed) {
            this.towardsAgent = towardsAgent;
            this.delay = delay;
            this.loss = loss;
            this.duplicates = duplicates;
            this.jitter = jitter;
            this.bandwidthBps = bandwidthBps;
            this.random = new Random(seed);
            this.linkFreeAt = now;
        }

        void send(byte[] datagram, Consumer<byte[]> deliver) {
            double lossDraw = random.nextDouble();
            long jitterDraw = jitter > 0 ? (long) (random.nextDouble() * jitter) : 0;
            boolean duplicate = random.nextDouble() < duplicates;
            long dupJitter = (long) (random.nextDouble() * (jitter + MS));
            if (lossDraw < loss) {
                stats.randomDrops++;
                if (towardsAgent) {
                    stats.ackDrops++;
                }
                return;
            }
            long leaves = now;
            if (bandwidthBps > 0) {
                long backlog = linkFreeAt - now;
                if (backlog < 0) {
                    backlog = 0;
                    linkFreeAt = now;
                }
                double queued = backlog * (bandwidthBps / 8d) / SECOND;
                if (queued + datagram.length > ROUTER_QUEUE) {
                    stats.overflowDrops++;
                    return;
                }
                linkFreeAt += (long) Math.ceil(datagram.length * 8d * SECOND / bandwidthBps);
                leaves = linkFreeAt;
            }
            long at = leaves + delay + jitterDraw;
            at(at, () -> deliver.accept(datagram));
            if (duplicate) {
                at(at + dupJitter, () -> deliver.accept(datagram.clone()));
            }
        }
    }

    final Config config;
    final Stats stats = new Stats();

    private final PriorityQueue<Event> events = new PriorityQueue<>(
            (a, b) -> a.time() != b.time() ? Long.compare(a.time() - T0, b.time() - T0) : Long.compare(a.seq(), b.seq()));
    private long seq;
    private long now = T0;
    private long eventCount;

    private final UdpPeer agentPeer;
    private final UdpPeer viewerPeer;
    private final UdpPacketCodec tap;
    private final UdpImageSender sender;
    private final UdpImageReceiver receiver;
    private final Path toViewer;
    private final Path toAgent;
    private final Random screenRandom;

    // agent
    private int epoch;
    private int width;
    private int height;
    private TileGrid grid;
    private long[] screen;
    private final BitSet dirty = new BitSet();
    private long version;
    private long captureSeq;
    private long finalCaptureSeq = -1;
    private boolean finalChangePending;
    private final Set<String> sentVersions = new HashSet<>();
    private long agentWakeAt;
    private boolean agentWakeSet;
    private final Map<Long, Long> submittedAt = new HashMap<>();

    // viewer
    private int viewerEpoch = -1;
    private TileVersions versions;
    private long[] viewerScreen;
    private long applyBusyUntil;
    private final Map<Long, Long> firstFragmentAt = new HashMap<>();
    private long viewerWakeAt;
    private boolean viewerWakeSet;
    private long applyBacklogRects;
    private long applyBacklogBytes;
    private long droppedOnPurpose = -1;
    private long gateClosedSince;
    private boolean gateClosed;

    SimulatedLink(Config config) {
        this.config = config;
        byte[] key = new byte[32];
        new Random(config.seed).nextBytes(key);
        agentPeer = new UdpPeer(Direction.AGENT, SESSION, key);
        viewerPeer = new UdpPeer(Direction.VIEWER, SESSION, key);
        tap = new UdpPacketCodec(key);
        sender = new UdpImageSender(agentPeer, now);
        receiver = new UdpImageReceiver(viewerPeer);
        toViewer = new Path(false, config.rttNanos / 2, config.loss, config.duplicates, config.jitterNanos,
                config.bandwidthBps, config.seed * 31 + 1);
        toAgent = new Path(true, config.rttNanos / 2, config.loss + config.ackLoss, config.duplicates, config.jitterNanos,
                0, config.seed * 31 + 2);
        screenRandom = new Random(config.seed * 31 + 3);
        applyBusyUntil = now;
    }

    /** Runs the whole scenario ({@link Config#runFor} of virtual time). */
    SimulatedLink run() {
        startEpoch(0, config.width, config.height);
        long end = T0 + config.runFor;
        at(T0, this::tick);
        while (!events.isEmpty() && events.peek().time() - end <= 0) {
            Event e = events.poll();
            now = e.time();
            if (++eventCount > MAX_EVENTS) {
                throw new AssertionError("event storm: " + eventCount + " events at " + rel(now));
            }
            e.action().run();
        }
        now = end;
        return this;
    }

    // ---- results ----

    /** Viewer tile versions equal the agent's for the current epoch, and nothing is in flight or pending. */
    boolean converged() {
        return viewerEpoch == epoch && sender.epoch() == epoch && viewerScreen != null
                && Arrays.equals(viewerScreen, screen) && idle();
    }

    String describe() {
        int differing = 0;
        if (viewerScreen != null && viewerScreen.length == screen.length) {
            for (int t = 0; t < screen.length; t++) {
                if (viewerScreen[t] != screen[t]) {
                    differing++;
                }
            }
        }
        return ("seed %d: epoch agent %d viewer %d, %d/%d tiles differ, outstanding %d B, queued %d, held %d B, "
                + "dirty %d, rate %d, convergedAt %s, drops random %d overflow %d tail %d, forced %d, resent %d, "
                + "timeouts %d, lost %d, dropped %d")
                .formatted(config.seed, epoch, viewerEpoch, differing, screen.length, sender.outstandingBytes(),
                        sender.queuedPackets(), receiver.heldBytes(), dirty.cardinality(),
                        sender.rateBitsPerSecond(), stats.convergedAt < 0 ? "never" : stats.convergedAt / MS + " ms",
                        stats.randomDrops, stats.overflowDrops, stats.tailDrops, stats.forcedTilesTaken,
                        stats.resentTileVersions, sender.confirmTimeouts(), sender.lostPackets(),
                        sender.droppedReports());
    }

    UdpImageSender sender() {
        return sender;
    }

    // ---- event queue ----

    private void at(long time, Runnable action) {
        events.add(new Event(time, seq++, action));
    }

    private static long rel(long t) {
        return t - T0;
    }

    // ---- agent ----

    private void startEpoch(int newEpoch, int w, int h) {
        epoch = newEpoch;
        width = w;
        height = h;
        grid = new TileGrid(w, h);
        screen = new long[grid.count()];
        for (int t = 0; t < screen.length; t++) {
            screen[t] = ++version; // full first frame
        }
        dirty.clear();
        dirty.set(0, screen.length);
        sender.newEpoch(newEpoch, w, h);
    }

    private void tick() {
        long t = rel(now);
        if (config.resizeAt >= 0 && t - config.resizeAt >= 0 && t - config.resizeAt < TICK) {
            startEpoch(epoch + 1, config.resizeWidth, config.resizeHeight);
            stats.lastChangeAt = t;
        }
        if (t < config.changesUntil) {
            for (int i = 0; i < config.changesPerTick; i++) {
                int tile = screenRandom.nextInt(screen.length);
                screen[tile] = ++version;
                dirty.set(tile);
            }
            stats.lastChangeAt = t;
        }
        if (config.finalChangeAt >= 0 && t - config.finalChangeAt >= 0 && t - config.finalChangeAt < TICK) {
            int tile = screenRandom.nextInt(screen.length);
            screen[tile] = ++version;
            dirty.set(tile);
            finalChangePending = true;
            stats.lastChangeAt = t;
        }
        boolean admits = sender.admitsCapture();
        if (!admits && !gateClosed) {
            gateClosed = true;
            gateClosedSince = now;
        } else if (admits && gateClosed) {
            gateClosed = false;
            stats.maxGateClosedNanos = Math.max(stats.maxGateClosedNanos, now - gateClosedSince);
        }
        if (admits) {
            TileDelivery.Forced forced = sender.takeForced();
            if (forced.epoch() == epoch) {
                stats.forcedTilesTaken += forced.tiles().cardinality();
                dirty.or(forced.tiles());
            }
            if (!dirty.isEmpty()) {
                capture();
            }
        }
        stats.sentSamples.add(new long[] {t, stats.agentSentBytes});
        stats.rateSamples.add(new long[] {t, sender.rateBitsPerSecond()});
        if (stats.convergedAt < 0 && t > stats.lastChangeAt && converged()) {
            stats.convergedAt = t;
        } else if (stats.convergedAt >= 0 && !converged()) {
            stats.convergedAt = -1;
        }
        at(now + TICK, this::tick);
    }

    private void capture() {
        captureSeq++;
        if (finalChangePending) {
            finalCaptureSeq = captureSeq;
            finalChangePending = false;
        }
        List<OutgoingRect> rects = new ArrayList<>();
        for (int row = 0; row < grid.rows(); row++) {
            int col = 0;
            while (col < grid.cols()) {
                if (!dirty.get(row * grid.cols() + col)) {
                    col++;
                    continue;
                }
                int start = col;
                while (col < grid.cols() && col - start < config.maxMergeTiles && dirty.get(row * grid.cols() + col)) {
                    col++;
                }
                rects.add(rect(row, start, col - start));
            }
        }
        dirty.clear();
        sender.submit(epoch, captureSeq, rects, now);
        if (config.timerGranularityNanos > 0) {
            submittedAt.put(captureSeq, now);
        }
        requestPump();
    }

    /** A pump caused by an event (capture, ACK): immediate, or at the next timer boundary with roundEveryPump. */
    private void requestPump() {
        if (config.timerGranularityNanos > 0 && config.roundEveryPump) {
            scheduleAgent(now);
        } else {
            pumpAgent();
        }
    }

    /** {@code t} rounded up to the agent's timer granularity (relative to the start, so overflow-safe). */
    private long timerAt(long t) {
        long g = config.timerGranularityNanos;
        if (g <= 0) {
            return t;
        }
        long r = rel(t);
        return T0 + Math.ceilDiv(r, g) * g;
    }

    private void measureQueueLatency(byte[] d) {
        Optional<UdpPacket> p = tap.decode(ByteBuffer.wrap(d), SESSION, Direction.AGENT);
        if (p.isEmpty() || p.get().type() != Type.TILE) {
            return;
        }
        TileFragment f = TileFragment.decode(p.get().payload()).orElseThrow();
        Long at = submittedAt.get(f.captureSeq());
        if (at != null) {
            stats.maxQueueLatencyNanos = Math.max(stats.maxQueueLatencyNanos, now - at);
        }
    }

    private OutgoingRect rect(int row, int col, int count) {
        int x = col * TileGrid.TILE;
        int y = row * TileGrid.TILE;
        int w = Math.min(count * TileGrid.TILE, width - x);
        int h = Math.min(TileGrid.TILE, height - y);
        int size = Math.max(4 + count * 12, count * config.bytesPerTile);
        ByteBuffer data = ByteBuffer.allocate(size).putInt(count);
        for (int i = 0; i < count; i++) {
            int tile = row * grid.cols() + col + i;
            data.putInt(tile).putLong(screen[tile]);
            if (!sentVersions.add(epoch + ":" + tile + ":" + screen[tile])) {
                stats.resentTileVersions++;
            }
        }
        return new OutgoingRect(x, y, w, h, TileFragment.LOSSLESS, data.array());
    }

    private void pumpAgent() {
        for (byte[] d : sender.poll(now)) {
            stats.agentSentBytes += d.length;
            stats.agentDatagrams++;
            stats.lastAgentSendAt = rel(now);
            if (config.timerGranularityNanos > 0) {
                measureQueueLatency(d);
            }
            if (dropAsTail(d)) {
                continue;
            }
            toViewer.send(d, this::onViewerDatagram);
        }
        scheduleAgent(sender.nextDeadlineNanos(now));
    }

    private boolean dropAsTail(byte[] d) {
        if (!config.loseFinalTail || stats.tailDrops > 0 || finalCaptureSeq < 0) {
            return false;
        }
        Optional<UdpPacket> p = tap.decode(ByteBuffer.wrap(d), SESSION, Direction.AGENT);
        if (p.isEmpty() || p.get().type() != Type.TILE) {
            return false;
        }
        TileFragment f = TileFragment.decode(p.get().payload()).orElseThrow();
        if (f.captureSeq() == finalCaptureSeq && f.offset() + f.data().length == f.totalLength()) {
            stats.tailDrops++;
            return true;
        }
        return false;
    }

    private void scheduleAgent(long deadline) {
        if (deadline - now < 0) {
            throw new AssertionError("agent deadline in the past");
        }
        long t = timerAt(deadline);
        if (agentWakeSet && agentWakeAt - t <= 0) {
            return;
        }
        agentWakeSet = true;
        agentWakeAt = t;
        at(t, () -> {
            if (agentWakeSet && agentWakeAt == t) {
                agentWakeSet = false;
                pumpAgent();
            }
        });
    }

    private void onAgentDatagram(byte[] d) {
        Optional<UdpPacket> p = agentPeer.open(ByteBuffer.wrap(d));
        if (p.isEmpty()) {
            stats.rejectedDatagrams++;
            return;
        }
        sender.onPacket(p.get(), now);
        requestPump();
    }

    // ---- viewer ----

    private void onViewerDatagram(byte[] d) {
        Optional<UdpPacket> opened = viewerPeer.open(ByteBuffer.wrap(d));
        if (opened.isEmpty()) {
            stats.rejectedDatagrams++;
            return;
        }
        UdpPacket p = opened.get();
        if (p.type() == Type.PROBE) {
            viewerPeer.answer(p).ifPresent(reply -> toAgent.send(reply, this::onAgentDatagram));
        }
        if (p.type() == Type.TILE) {
            TileFragment.decode(p.payload()).ifPresent(f -> firstFragmentAt.putIfAbsent(f.rectId(), now));
        }
        receiver.onPacket(p, now).ifPresent(rect -> {
            Long first = firstFragmentAt.remove(rect.rectId());
            if (first != null) {
                stats.maxAssemblyNanos = Math.max(stats.maxAssemblyNanos, now - first);
            }
            if (config.applyNanos <= 0) {
                apply(rect);
            } else {
                if (applyBusyUntil - now < 0 || !config.applySerial) {
                    applyBusyUntil = now;
                }
                applyBusyUntil += config.applyNanos;
                applyBacklogRects++;
                applyBacklogBytes += rect.data().length;
                stats.maxApplyBacklogRects = Math.max(stats.maxApplyBacklogRects, applyBacklogRects);
                stats.maxApplyBacklogBytes = Math.max(stats.maxApplyBacklogBytes, applyBacklogBytes);
                at(applyBusyUntil, () -> {
                    applyBacklogRects--;
                    applyBacklogBytes -= rect.data().length;
                    apply(rect);
                    pumpViewer();
                });
            }
        });
        pumpViewer();
    }

    private void apply(CompleteRect r) {
        if (droppedOnPurpose < 0 && r.captureSeq() == config.dropRectOfCapture) {
            droppedOnPurpose = r.rectId();
            receiver.dropped(r.rectId());
            return;
        }
        if (r.epoch() < viewerEpoch) {
            receiver.dropped(r.rectId());
            return;
        }
        if (r.epoch() > viewerEpoch) {
            viewerEpoch = r.epoch();
            TileGrid g = new TileGrid(r.streamWidth(), r.streamHeight());
            versions = new TileVersions(g);
            viewerScreen = new long[g.count()];
        }
        ByteBuffer in = ByteBuffer.wrap(r.data());
        int count = in.getInt();
        Map<Integer, Long> carried = new HashMap<>();
        for (int i = 0; i < count; i++) {
            carried.put(in.getInt(), in.getLong());
        }
        BitSet claimed = versions.claimNewer(r.x(), r.y(), r.width(), r.height(), r.captureSeq());
        for (int t = claimed.nextSetBit(0); t >= 0; t = claimed.nextSetBit(t + 1)) {
            Long v = carried.get(t);
            if (v == null) {
                throw new AssertionError("rect " + r + " does not carry tile " + t);
            }
            viewerScreen[t] = v;
        }
        receiver.applied(r.rectId());
    }

    private void pumpViewer() {
        for (byte[] d : receiver.poll(now)) {
            if (config.loseDroppedReport && carriesDroppedOnPurpose(d)) {
                stats.droppedReportsLost++;
                continue;
            }
            toAgent.send(d, this::onAgentDatagram);
        }
        scheduleViewer(receiver.nextDeadlineNanos(now));
    }

    private void scheduleViewer(long t) {
        if (t - now < 0) {
            throw new AssertionError("viewer deadline in the past");
        }
        if (viewerWakeSet && viewerWakeAt - t <= 0) {
            return;
        }
        viewerWakeSet = true;
        viewerWakeAt = t;
        at(t, () -> {
            if (viewerWakeSet && viewerWakeAt == t) {
                viewerWakeSet = false;
                pumpViewer();
            }
        });
    }

    private boolean carriesDroppedOnPurpose(byte[] d) {
        if (droppedOnPurpose < 0) {
            return false;
        }
        Optional<UdpPacket> p = tap.decode(ByteBuffer.wrap(d), SESSION, Direction.VIEWER);
        return p.isPresent() && p.get().type() == Type.ACK
                && AckFrame.decode(p.get().payload()).orElseThrow().droppedRects().contains(droppedOnPurpose);
    }

    private boolean idle() {
        return sender.outstandingBytes() == 0 && sender.queuedPackets() == 0 && receiver.heldBytes() == 0
                && dirty.isEmpty() && !finalChangePending && !sender.hasForcedTiles();
    }
}

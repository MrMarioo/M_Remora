package pl.mtech.mremora.protocol.udp;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Reassembles {@link TileFragment}s into {@link CompleteRect}s. Single-threaded, time only from {@code nowNanos}.
 * <ul>
 *   <li>An incomplete rectangle expires {@code expireNanos} after its <b>last</b> received fragment (a slow link
 *       must be able to deliver a large rectangle over more than one expiry period).</li>
 *   <li>Buffers of incomplete rectangles are capped at {@code maxBytes}; the oldest (first-seen) are dropped.</li>
 *   <li>Duplicate and overlapping fragments are ignored; a fragment whose header contradicts the stored one drops
 *       the whole rectangle.</li>
 *   <li>A fragment of an older epoch is ignored; of a newer one it advances the epoch and drops every incomplete
 *       rectangle of the older epoch.</li>
 * </ul>
 * A rectId that was completed, dropped or expired is remembered (in a pruned {@link RangeSet}) and its later
 * fragments are ignored.
 */
public final class RectAssembler {

    public static final long EXPIRE_NANOS = 1_000_000_000L;
    public static final int MAX_BYTES = 8 << 20;

    /** How far below the highest finished rectId the memory of finished ids is kept. */
    private static final long REMEMBER = 4096;

    /** Upper bound on simultaneously incomplete rectangles (per-partial bookkeeping is not covered by maxBytes). */
    public static final int MAX_PARTIALS = 4096;

    /** Header of a rectangle without the fragment bytes (the first fragment is not retained). */
    private record Header(int epoch, int streamWidth, int streamHeight, long captureSeq, long rectId,
                          int x, int y, int width, int height, int encoding, int totalLength) {
        static Header of(TileFragment f) {
            return new Header(f.epoch(), f.streamWidth(), f.streamHeight(), f.captureSeq(), f.rectId(),
                    f.x(), f.y(), f.width(), f.height(), f.encoding(), f.totalLength());
        }
    }

    private static final class Partial {
        final Header header;
        final byte[] buffer;
        final TreeMap<Integer, Integer> received = new TreeMap<>(); // start -> end (exclusive), disjoint
        int receivedBytes;
        long lastNanos;

        Partial(TileFragment first) {
            header = Header.of(first);
            buffer = new byte[first.totalLength()];
        }

        boolean sameHeader(TileFragment f) {
            return header.equals(Header.of(f));
        }

        boolean overlaps(int start, int end) {
            Map.Entry<Integer, Integer> before = received.lowerEntry(end);
            return before != null && before.getValue() > start;
        }
    }

    private final long expireNanos;
    private final int maxBytes;
    private final LinkedHashMap<Long, Partial> partials = new LinkedHashMap<>(); // oldest first
    private final RangeSet finished = new RangeSet();
    private final List<Long> dropped = new ArrayList<>();
    private int heldBytes;
    private int epoch = -1;

    public RectAssembler(long expireNanos, int maxBytes) {
        this.expireNanos = expireNanos;
        this.maxBytes = maxBytes;
    }

    /** @return the rectangle if this fragment completed it */
    public Optional<CompleteRect> add(TileFragment f, long nowNanos) {
        if (f.epoch() < epoch) {
            if (!finished.contains(f.rectId())) {
                markFinished(f.rectId());
                dropped.add(f.rectId());
            }
            return Optional.empty();
        }
        if (f.epoch() > epoch) {
            epoch = f.epoch();
            for (Long id : partials.keySet()) {
                markFinished(id);
                dropped.add(id);
            }
            partials.clear();
            heldBytes = 0;
        }
        if (finished.contains(f.rectId())) {
            return Optional.empty();
        }
        Partial p = partials.get(f.rectId());
        if (p == null) {
            if (f.totalLength() > maxBytes) {
                markFinished(f.rectId());
                dropped.add(f.rectId());
                return Optional.empty();
            }
            evictFor(f.totalLength());
            while (partials.size() >= MAX_PARTIALS) {
                evictOldest();
            }
            p = new Partial(f);
            partials.put(f.rectId(), p);
            heldBytes += f.totalLength();
        } else if (!p.sameHeader(f)) {
            remove(f.rectId(), p);
            markFinished(f.rectId());
            dropped.add(f.rectId());
            return Optional.empty();
        }
        int start = f.offset();
        int end = start + f.data().length;
        if (p.overlaps(start, end)) {
            return Optional.empty();
        }
        p.lastNanos = nowNanos; // only new bytes keep a rectangle alive; replays must not
        System.arraycopy(f.data(), 0, p.buffer, start, f.data().length);
        p.received.put(start, end);
        p.receivedBytes += f.data().length;
        if (p.receivedBytes < p.header.totalLength()) {
            return Optional.empty();
        }
        remove(f.rectId(), p);
        markFinished(f.rectId());
        Header h = p.header;
        return Optional.of(new CompleteRect(h.epoch(), h.streamWidth(), h.streamHeight(), h.captureSeq(),
                h.rectId(), h.x(), h.y(), h.width(), h.height(), h.encoding(), p.buffer));
    }

    /** Drops rectangles whose last fragment arrived {@code expireNanos} or more ago; returns their ids. */
    public List<Long> expire(long nowNanos) {
        List<Long> out = new ArrayList<>();
        for (Iterator<Map.Entry<Long, Partial>> it = partials.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Long, Partial> e = it.next();
            if (nowNanos - e.getValue().lastNanos >= expireNanos) {
                it.remove();
                heldBytes -= e.getValue().buffer.length;
                markFinished(e.getKey());
                out.add(e.getKey());
            }
        }
        return out;
    }

    /** Ids dropped by the memory cap, a contradicting header or an epoch change since the last call. */
    public List<Long> takeDropped() {
        List<Long> out = new ArrayList<>(dropped);
        dropped.clear();
        return out;
    }

    /** The newest epoch seen, -1 before the first fragment. */
    public int currentEpoch() {
        return epoch;
    }

    /** Bytes buffered for incomplete rectangles. */
    public int heldBytes() {
        return heldBytes;
    }

    private void evictFor(int needed) {
        while (heldBytes + needed > maxBytes && !partials.isEmpty()) {
            evictOldest();
        }
    }

    private void evictOldest() {
        Iterator<Map.Entry<Long, Partial>> it = partials.entrySet().iterator();
        Map.Entry<Long, Partial> e = it.next();
        it.remove();
        heldBytes -= e.getValue().buffer.length;
        markFinished(e.getKey());
        dropped.add(e.getKey());
    }

    private void remove(long rectId, Partial p) {
        partials.remove(rectId);
        heldBytes -= p.buffer.length;
    }

    private void markFinished(long rectId) {
        finished.add(rectId);
        finished.removeBelow(finished.max() - REMEMBER);
    }
}

package pl.mtech.mremora.agent;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Objects;
import pl.mtech.mremora.protocol.RectPixels;
import pl.mtech.mremora.protocol.StreamConfig;
import pl.mtech.mremora.protocol.TileUpdate;
import pl.mtech.mremora.protocol.TileUpdate.Encoding;
import pl.mtech.mremora.protocol.TileUpdate.Rect;
import pl.mtech.mremora.protocol.udp.TileDelivery;
import pl.mtech.mremora.protocol.udp.TileFragment;
import pl.mtech.mremora.protocol.udp.UdpImageSender;

/**
 * Produces tile frames: captures, scales, diffs against the viewer's reference image and encodes the changed
 * rectangles. {@link #next()} (v2, TCP) splits them into parts of at most {@link #MAX_PART_BYTES};
 * {@link #nextForUdp} (v3 over UDP) returns them as one {@link UdpFrame} of smaller rectangles, together with the
 * tiles the UDP sender forced. Both share one reference image and one sequence.
 *
 * <p>Epoch: starts at 0 and grows by one exactly where an existing reference is dropped because the stream size
 * changed; the first frame and a {@code StreamConfig} that yields the same size keep it.
 *
 * <p>{@link #next()} and {@link #nextForUdp} must be called from one thread; {@link #configure} and {@link #epoch()}
 * may be called from any.
 */
final class TileFrameEncoder {

    static final int MAX_PART_BYTES = 256 * 1024;
    private static final int PART_HEADER_BYTES = 17;
    private static final int RECT_HEADER_BYTES = 13;

    /**
     * One capture for the UDP sender.
     *
     * @param epoch      the encoder's epoch of this capture's stream size
     * @param captureSeq the frame sequence, growing with every non-empty frame
     * @param rects      tile-aligned rectangles; empty when nothing changed and nothing was forced
     */
    record UdpFrame(int epoch, int streamWidth, int streamHeight, long captureSeq,
                    List<UdpImageSender.OutgoingRect> rects) {
        UdpFrame {
            rects = List.copyOf(rects);
        }
    }

    private record Encoded(long sequence, int width, int height, List<RectPlanner.PlannedRect> planned,
                           List<byte[]> data) {}

    private final ScreenCapturer capturer;
    private final FrameScaler scaler = new FrameScaler();
    private final JpegFrameEncoder jpeg = new JpegFrameEncoder(JpegFrameEncoder.DEFAULT_QUALITY);
    private volatile StreamConfig config = StreamConfig.FULL_RESOLUTION;
    private int[] reference;
    private StreamSize referenceSize;
    private long sequence;
    private volatile int epoch;

    TileFrameEncoder(ScreenCapturer capturer) {
        this.capturer = capturer;
    }

    /** Takes effect at the next {@link #next()}. */
    void configure(StreamConfig config) {
        this.config = Objects.requireNonNull(config, "config");
    }

    /** The current epoch (see the class description); any thread. */
    int epoch() {
        return epoch;
    }

    /** @return the parts of one frame, or an empty list when nothing changed */
    List<TileUpdate> next() {
        Encoded e = encodeNext(null, RectPlanner.MAX_MERGE_TILES);
        if (e == null) {
            return List.of();
        }
        var groups = new ArrayList<List<Rect>>();
        var current = new ArrayList<Rect>();
        int bytes = PART_HEADER_BYTES;
        for (int i = 0; i < e.planned().size(); i++) {
            var p = e.planned().get(i);
            Rect rect = new Rect(p.x(), p.y(), p.width(), p.height(), p.encoding(), e.data().get(i));
            int cost = RECT_HEADER_BYTES + rect.dataLength();
            if (!current.isEmpty() && bytes + cost > MAX_PART_BYTES) {
                groups.add(current);
                current = new ArrayList<>();
                bytes = PART_HEADER_BYTES;
            }
            current.add(rect);
            bytes += cost;
        }
        groups.add(current);

        var parts = new ArrayList<TileUpdate>(groups.size());
        for (int i = 0; i < groups.size(); i++) {
            parts.add(new TileUpdate(e.sequence(), e.width(), e.height(), i == groups.size() - 1, 0, groups.get(i)));
        }
        return parts;
    }

    /**
     * Like {@link #next()}, but for the UDP sender: the changed tiles plus {@code forced} (tiles whose last sent
     * version was lost; ignored when they belong to another epoch than this capture's), in rectangles of at most
     * {@link RectPlanner#UDP_MAX_MERGE_TILES} tiles.
     */
    UdpFrame nextForUdp(TileDelivery.Forced forced) {
        return nextForUdp(forced, RectPlanner.UDP_MAX_MERGE_TILES);
    }

    /** {@link #nextForUdp(TileDelivery.Forced)} with another merge width; for the benchmark that chose it. */
    UdpFrame nextForUdp(TileDelivery.Forced forced, int maxMergeTiles) {
        Objects.requireNonNull(forced, "forced");
        Encoded e = encodeNext(forced, maxMergeTiles);
        if (e == null) {
            StreamSize size = referenceSize;
            return new UdpFrame(epoch, size.width(), size.height(), sequence, List.of());
        }
        var rects = new ArrayList<UdpImageSender.OutgoingRect>(e.planned().size());
        for (int i = 0; i < e.planned().size(); i++) {
            var p = e.planned().get(i);
            rects.add(new UdpImageSender.OutgoingRect(p.x(), p.y(), p.width(), p.height(), udpEncoding(p.encoding()),
                    e.data().get(i)));
        }
        return new UdpFrame(epoch, e.width(), e.height(), e.sequence(), rects);
    }

    /** Captures and encodes the dirty (and forced) tiles; {@code null} when there is nothing to send. */
    private Encoded encodeNext(TileDelivery.Forced forced, int maxMergeTiles) {
        StreamConfig cfg = config;
        var capture = capturer.capture();
        StreamSize size = StreamSize.fit(capture.getWidth(), capture.getHeight(), cfg);
        if (!size.equals(referenceSize)) {
            if (reference != null) {
                epoch++; // the viewer's image of the old size is gone: a new epoch
            }
            reference = null;
            referenceSize = size;
        }
        int w = size.width();
        int h = size.height();
        int[] pixels = scaler.toStreamPixels(capture, size);
        BitSet dirty = DirtyTiles.find(pixels, reference, w, h);
        if (forced != null && forced.epoch() == epoch) {
            dirty.or(forced.tiles().get(0, DirtyTiles.cols(w) * DirtyTiles.rows(h)));
        }
        if (dirty.isEmpty()) {
            return null;
        }
        List<RectPlanner.PlannedRect> planned = RectPlanner.plan(pixels, w, h, dirty, maxMergeTiles);
        var data = new ArrayList<byte[]>(planned.size());
        for (var p : planned) {
            data.add(encode(p, pixels, w));
        }

        // Only after every rectangle encoded: a thrown encode must not leave unsent tiles in the reference.
        if (reference == null) {
            reference = new int[w * h];
        }
        for (var p : planned) {
            for (int y = p.y(); y < p.y() + p.height(); y++) {
                System.arraycopy(pixels, y * w + p.x(), reference, y * w + p.x(), p.width());
            }
        }
        return new Encoded(sequence++, w, h, planned, data);
    }

    private static int udpEncoding(Encoding encoding) {
        return switch (encoding) {
            case SOLID -> TileFragment.SOLID;
            case LOSSLESS -> TileFragment.LOSSLESS;
            case JPEG -> TileFragment.JPEG;
            case COPY -> throw new IllegalStateException("COPY is not planned yet");
        };
    }

    private byte[] encode(RectPlanner.PlannedRect p, int[] pixels, int stride) {
        return switch (p.encoding()) {
            case SOLID -> RectPixels.encodeSolid(p.solidColor());
            case LOSSLESS -> RectPixels.encodeLossless(pixels, stride, p.x(), p.y(), p.width(), p.height());
            case JPEG -> jpeg.encodeRegion(pixels, stride, p.x(), p.y(), p.width(), p.height());
            case COPY -> throw new IllegalStateException("COPY is not planned yet");
        };
    }
}

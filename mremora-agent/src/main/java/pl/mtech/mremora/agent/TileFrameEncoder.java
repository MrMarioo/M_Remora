package pl.mtech.mremora.agent;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Objects;
import pl.mtech.mremora.protocol.RectPixels;
import pl.mtech.mremora.protocol.StreamConfig;
import pl.mtech.mremora.protocol.TileUpdate;
import pl.mtech.mremora.protocol.TileUpdate.Rect;

/**
 * Produces v2 frames: captures, scales, diffs against the viewer's reference image, encodes the changed
 * rectangles and splits them into parts of at most {@link #MAX_PART_BYTES}. {@link #next()} must be called from
 * one thread; {@link #configure} may be called from any.
 */
final class TileFrameEncoder {

    static final int MAX_PART_BYTES = 256 * 1024;
    private static final int PART_HEADER_BYTES = 17;
    private static final int RECT_HEADER_BYTES = 13;

    private final ScreenCapturer capturer;
    private final FrameScaler scaler = new FrameScaler();
    private final JpegFrameEncoder jpeg = new JpegFrameEncoder(JpegFrameEncoder.DEFAULT_QUALITY);
    private volatile StreamConfig config = StreamConfig.FULL_RESOLUTION;
    private int[] reference;
    private StreamSize referenceSize;
    private long sequence;

    TileFrameEncoder(ScreenCapturer capturer) {
        this.capturer = capturer;
    }

    /** Takes effect at the next {@link #next()}. */
    void configure(StreamConfig config) {
        this.config = Objects.requireNonNull(config, "config");
    }

    /** @return the parts of one frame, or an empty list when nothing changed */
    List<TileUpdate> next() {
        StreamConfig cfg = config;
        var capture = capturer.capture();
        StreamSize size = StreamSize.fit(capture.getWidth(), capture.getHeight(), cfg);
        if (!size.equals(referenceSize)) {
            reference = null;
            referenceSize = size;
        }
        int w = size.width();
        int h = size.height();
        int[] pixels = scaler.toStreamPixels(capture, size);
        BitSet dirty = DirtyTiles.find(pixels, reference, w, h);
        if (dirty.isEmpty()) {
            return List.of();
        }
        List<RectPlanner.PlannedRect> planned = RectPlanner.plan(pixels, w, h, dirty);

        long seq = sequence++;
        var groups = new ArrayList<List<Rect>>();
        var current = new ArrayList<Rect>();
        int bytes = PART_HEADER_BYTES;
        for (var p : planned) {
            Rect rect = new Rect(p.x(), p.y(), p.width(), p.height(), p.encoding(), encode(p, pixels, w));
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

        // Only after every rectangle encoded: a thrown encode must not leave unsent tiles in the reference.
        if (reference == null) {
            reference = new int[w * h];
        }
        for (var p : planned) {
            for (int y = p.y(); y < p.y() + p.height(); y++) {
                System.arraycopy(pixels, y * w + p.x(), reference, y * w + p.x(), p.width());
            }
        }

        var parts = new ArrayList<TileUpdate>(groups.size());
        for (int i = 0; i < groups.size(); i++) {
            parts.add(new TileUpdate(seq, w, h, i == groups.size() - 1, 0, groups.get(i)));
        }
        return parts;
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

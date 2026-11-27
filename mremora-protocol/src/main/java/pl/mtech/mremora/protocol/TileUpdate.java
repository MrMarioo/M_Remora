package pl.mtech.mremora.protocol;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * A batch of changed rectangles of the stream image. {@code sequence} grows per batch;
 * {@code lastPart} marks the final message of one frame; {@code latencyMs} is the agent's
 * capture-to-send time. Rectangles lie inside {@code streamWidth x streamHeight}.
 * The sequence is an unsigned 64-bit value on the wire; any {@code long} is accepted.
 */
public record TileUpdate(long sequence, int streamWidth, int streamHeight, boolean lastPart, int latencyMs,
                         List<Rect> rects) implements Message {

    public enum Encoding { SOLID, LOSSLESS, JPEG, COPY }

    /**
     * One rectangle. The payload is not interpreted by the codec. {@code data} is not copied
     * (hot path): the caller must not modify the array after creating the rectangle.
     */
    public record Rect(int x, int y, int width, int height, Encoding encoding, byte[] data) {

        public Rect {
            requireU16(x, 0, "x");
            requireU16(y, 0, "y");
            requireU16(width, 1, "width");
            requireU16(height, 1, "height");
            Objects.requireNonNull(encoding, "encoding");
            Objects.requireNonNull(data, "data");
        }

        /** Payload size in bytes. */
        public int dataLength() {
            return data.length;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Rect(int x1, int y1, int w, int h, Encoding e, byte[] d)
                    && x1 == x && y1 == y && w == width && h == height && e == encoding && Arrays.equals(d, data);
        }

        @Override
        public int hashCode() {
            return Objects.hash(x, y, width, height, encoding, Arrays.hashCode(data));
        }

        @Override
        public String toString() {
            return "Rect[x=%d, y=%d, width=%d, height=%d, encoding=%s, %d bytes]"
                    .formatted(x, y, width, height, encoding, data.length);
        }
    }

    public TileUpdate {
        requireU16(streamWidth, 1, "streamWidth");
        requireU16(streamHeight, 1, "streamHeight");
        requireU16(latencyMs, 0, "latencyMs");
        rects = List.copyOf(rects);
        if (rects.size() > 0xFFFF) {
            throw new IllegalArgumentException("Too many rectangles: " + rects.size());
        }
        for (Rect r : rects) {
            if (r.x() + r.width() > streamWidth || r.y() + r.height() > streamHeight) {
                throw new IllegalArgumentException("%s does not fit in the %dx%d stream".formatted(r, streamWidth, streamHeight));
            }
        }
    }

    private static void requireU16(int value, int min, String what) {
        if (value < min || value > 0xFFFF) {
            throw new IllegalArgumentException("%s must be %d..65535, was %d".formatted(what, min, value));
        }
    }
}

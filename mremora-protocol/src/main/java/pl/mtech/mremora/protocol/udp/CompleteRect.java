package pl.mtech.mremora.protocol.udp;

import java.util.Arrays;
import java.util.Objects;

/**
 * A fully assembled encoded rectangle (the sender's input to {@link RectFragmenter}, the receiver's output of
 * {@link RectAssembler}). {@code data} is not copied; callers must not modify it afterwards.
 *
 * @param encoding {@link TileFragment#SOLID}, {@link TileFragment#LOSSLESS} or {@link TileFragment#JPEG}
 */
public record CompleteRect(int epoch, int streamWidth, int streamHeight, long captureSeq, long rectId,
                           int x, int y, int width, int height, int encoding, byte[] data) {

    public CompleteRect {
        Objects.requireNonNull(data, "data");
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof CompleteRect r
                && epoch == r.epoch && streamWidth == r.streamWidth && streamHeight == r.streamHeight
                && captureSeq == r.captureSeq && rectId == r.rectId && x == r.x && y == r.y
                && width == r.width && height == r.height && encoding == r.encoding
                && Arrays.equals(data, r.data);
    }

    @Override
    public int hashCode() {
        return Objects.hash(epoch, streamWidth, streamHeight, captureSeq, rectId, x, y, width, height, encoding,
                Arrays.hashCode(data));
    }

    @Override
    public String toString() {
        return "CompleteRect[epoch=%d, stream=%dx%d, seq=%d, rect=%d at %d,%d %dx%d, enc=%d, %d B]"
                .formatted(epoch, streamWidth, streamHeight, captureSeq, rectId, x, y, width, height, encoding,
                        data.length);
    }
}

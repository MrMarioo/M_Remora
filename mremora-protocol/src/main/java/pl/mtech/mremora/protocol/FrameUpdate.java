package pl.mtech.mremora.protocol;

import java.util.Arrays;
import java.util.Objects;

/**
 * One encoded screen image. {@code sequence} grows by one per captured frame,
 * so the viewer can tell which frames were dropped.
 */
public record FrameUpdate(long sequence, Format format, byte[] data) implements Message {

    public enum Format { JPEG }

    public FrameUpdate {
        Objects.requireNonNull(format, "format");
        data = data.clone();
    }

    @Override
    public byte[] data() {
        return data.clone();
    }

    /** Payload size without copying the data. */
    public int dataLength() {
        return data.length;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof FrameUpdate(long s, Format f, byte[] d)
                && s == sequence && f == format && Arrays.equals(d, data);
    }

    @Override
    public int hashCode() {
        return Objects.hash(sequence, format, Arrays.hashCode(data));
    }

    @Override
    public String toString() {
        return "FrameUpdate[sequence=%d, format=%s, %d bytes]".formatted(sequence, format, data.length);
    }
}

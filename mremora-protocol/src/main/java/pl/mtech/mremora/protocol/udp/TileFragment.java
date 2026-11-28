package pl.mtech.mremora.protocol.udp;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;

/**
 * Payload of a {@code TILE} packet: one fragment of an encoded image rectangle (big-endian):
 * {@code u16 epoch | u16 streamWidth | u16 streamHeight | u64 captureSeq | u32 rectId | u16 x | u16 y | u16 w | u16 h |
 * u8 encoding (1 SOLID, 2 LOSSLESS, 3 JPEG) | u32 totalLength | u32 offset | fragment bytes}.
 * The constructor enforces the wire rules, so {@link #decode} returns empty for anything it would refuse.
 * {@code data} is not copied (hot path) - callers must not modify it afterwards.
 *
 * @param rectId rectangle number, increasing from 1 within a session (u32)
 */
public record TileFragment(int epoch, int streamWidth, int streamHeight, long captureSeq, long rectId,
                           int x, int y, int width, int height, int encoding,
                           int totalLength, int offset, byte[] data) {

    public static final int HEADER = 35;
    public static final int MAX_DATA = UdpPacketCodec.MAX_PAYLOAD - HEADER;
    public static final int MAX_RECT_BYTES = 1 << 20;

    public static final int SOLID = 1;
    public static final int LOSSLESS = 2;
    public static final int JPEG = 3;

    private static final int U16 = 0xFFFF;
    private static final long U32 = 0xFFFFFFFFL;

    public TileFragment {
        Objects.requireNonNull(data, "data");
        require(epoch >= 0 && epoch <= U16, "epoch");
        require(streamWidth >= 1 && streamWidth <= U16, "streamWidth");
        require(streamHeight >= 1 && streamHeight <= U16, "streamHeight");
        require(captureSeq >= 0, "captureSeq");
        require(rectId >= 1 && rectId <= U32, "rectId");
        require(width >= 1 && height >= 1, "size");
        require(x >= 0 && y >= 0 && x + (long) width <= streamWidth && y + (long) height <= streamHeight, "rect");
        require(encoding >= SOLID && encoding <= JPEG, "encoding");
        require(totalLength >= 1 && totalLength <= MAX_RECT_BYTES, "totalLength");
        require(data.length >= 1 && data.length <= MAX_DATA, "data length");
        require(offset >= 0 && (long) offset + data.length <= totalLength, "offset");
    }

    private static void require(boolean ok, String what) {
        if (!ok) {
            throw new IllegalArgumentException("invalid tile fragment: " + what);
        }
    }

    public byte[] encode() {
        return ByteBuffer.allocate(HEADER + data.length)
                .putShort((short) epoch).putShort((short) streamWidth).putShort((short) streamHeight)
                .putLong(captureSeq).putInt((int) rectId)
                .putShort((short) x).putShort((short) y).putShort((short) width).putShort((short) height)
                .put((byte) encoding).putInt(totalLength).putInt(offset)
                .put(data)
                .array();
    }

    /** Never throws for malformed input; empty for anything that is not a valid fragment. */
    public static Optional<TileFragment> decode(byte[] payload) {
        if (payload.length <= HEADER || payload.length > UdpPacketCodec.MAX_PAYLOAD) {
            return Optional.empty();
        }
        ByteBuffer in = ByteBuffer.wrap(payload);
        int epoch = in.getShort() & U16;
        int streamWidth = in.getShort() & U16;
        int streamHeight = in.getShort() & U16;
        long captureSeq = in.getLong();
        long rectId = in.getInt() & U32;
        int x = in.getShort() & U16;
        int y = in.getShort() & U16;
        int w = in.getShort() & U16;
        int h = in.getShort() & U16;
        int encoding = in.get() & 0xFF;
        long total = in.getInt() & U32;
        long offset = in.getInt() & U32;
        if (total > MAX_RECT_BYTES || offset > MAX_RECT_BYTES) {
            return Optional.empty();
        }
        try {
            return Optional.of(new TileFragment(epoch, streamWidth, streamHeight, captureSeq, rectId, x, y, w, h,
                    encoding, (int) total, (int) offset, Arrays.copyOfRange(payload, HEADER, payload.length)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof TileFragment t
                && epoch == t.epoch && streamWidth == t.streamWidth && streamHeight == t.streamHeight
                && captureSeq == t.captureSeq && rectId == t.rectId && x == t.x && y == t.y
                && width == t.width && height == t.height && encoding == t.encoding
                && totalLength == t.totalLength && offset == t.offset && Arrays.equals(data, t.data);
    }

    @Override
    public int hashCode() {
        return Objects.hash(epoch, streamWidth, streamHeight, captureSeq, rectId, x, y, width, height, encoding,
                totalLength, offset, Arrays.hashCode(data));
    }

    @Override
    public String toString() {
        return "TileFragment[epoch=%d, stream=%dx%d, seq=%d, rect=%d at %d,%d %dx%d, enc=%d, %d+%d/%d]"
                .formatted(epoch, streamWidth, streamHeight, captureSeq, rectId, x, y, width, height, encoding,
                        offset, data.length, totalLength);
    }
}

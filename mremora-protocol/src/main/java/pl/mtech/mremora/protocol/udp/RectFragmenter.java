package pl.mtech.mremora.protocol.udp;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Cuts an encoded rectangle into {@link TileFragment}s of at most {@link TileFragment#MAX_DATA} bytes. */
public final class RectFragmenter {

    private RectFragmenter() {
    }

    /**
     * Fragments with increasing {@code offset}.
     *
     * @throws IllegalArgumentException for an empty rectangle, one above {@link TileFragment#MAX_RECT_BYTES}, or
     *                                  header values the wire format cannot carry
     */
    public static List<TileFragment> split(CompleteRect rect) {
        byte[] data = rect.data();
        if (data.length == 0 || data.length > TileFragment.MAX_RECT_BYTES) {
            throw new IllegalArgumentException("rectangle size out of range: " + data.length);
        }
        List<TileFragment> out = new ArrayList<>((data.length + TileFragment.MAX_DATA - 1) / TileFragment.MAX_DATA);
        for (int offset = 0; offset < data.length; offset += TileFragment.MAX_DATA) {
            int end = Math.min(data.length, offset + TileFragment.MAX_DATA);
            out.add(new TileFragment(rect.epoch(), rect.streamWidth(), rect.streamHeight(), rect.captureSeq(),
                    rect.rectId(), rect.x(), rect.y(), rect.width(), rect.height(), rect.encoding(),
                    data.length, offset, Arrays.copyOfRange(data, offset, end)));
        }
        return out;
    }
}

package pl.mtech.mremora.agent;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import pl.mtech.mremora.protocol.TileUpdate.Encoding;

/** Classifies dirty tiles and merges horizontal runs of the same class into rectangles. */
final class RectPlanner {

    static final int MAX_MERGE_TILES = 8;
    static final int LOSSLESS_MAX_COLORS = 64;

    /** {@code solidColor} is meaningful only for {@link Encoding#SOLID}. */
    record PlannedRect(int x, int y, int width, int height, Encoding encoding, int solidColor) {
    }

    private RectPlanner() {
    }

    static List<PlannedRect> plan(int[] pixels, int width, int height, BitSet dirty) {
        int cols = DirtyTiles.cols(width);
        int rows = DirtyTiles.rows(height);
        var out = new ArrayList<PlannedRect>();
        int[] scratch = new int[LOSSLESS_MAX_COLORS * 2];
        for (int row = 0; row < rows; row++) {
            int col = 0;
            while (col < cols) {
                if (!dirty.get(row * cols + col)) {
                    col++;
                    continue;
                }
                int color = classify(pixels, width, height, col, row, scratch);
                Encoding encoding = encodingOf(color);
                int solid = color >= 0 ? color : 0;
                int end = col + 1;
                while (end < cols && end - col < MAX_MERGE_TILES && dirty.get(row * cols + end)
                        && classify(pixels, width, height, end, row, scratch) == color) {
                    end++;
                }
                int x = col * DirtyTiles.TILE;
                int y = row * DirtyTiles.TILE;
                out.add(new PlannedRect(x, y, Math.min(end * DirtyTiles.TILE, width) - x,
                        Math.min(y + DirtyTiles.TILE, height) - y, encoding, solid));
                col = end;
            }
        }
        return out;
    }

    // Class code: >= 0 is a SOLID color; LOSSLESS_CLASS / JPEG_CLASS are negative markers.
    private static final int LOSSLESS_CLASS = -1;
    private static final int JPEG_CLASS = -2;

    private static Encoding encodingOf(int cls) {
        return cls >= 0 ? Encoding.SOLID : cls == LOSSLESS_CLASS ? Encoding.LOSSLESS : Encoding.JPEG;
    }

    private static int classify(int[] pixels, int width, int height, int col, int row, int[] table) {
        int x0 = col * DirtyTiles.TILE;
        int y0 = row * DirtyTiles.TILE;
        int x1 = Math.min(x0 + DirtyTiles.TILE, width);
        int y1 = Math.min(y0 + DirtyTiles.TILE, height);
        int first = pixels[y0 * width + x0] & 0xFFFFFF;
        int mask = table.length - 1; // table length is a power of two
        Arrays.fill(table, -1);
        int distinct = 0;
        for (int y = y0; y < y1; y++) {
            int base = y * width;
            for (int x = x0; x < x1; x++) {
                int c = pixels[base + x] & 0xFFFFFF;
                int slot = (c * 0x9E3779B1) >>> 7 & mask;
                while (table[slot] != -1 && table[slot] != c) {
                    slot = (slot + 1) & mask;
                }
                if (table[slot] == -1) {
                    table[slot] = c;
                    if (++distinct > LOSSLESS_MAX_COLORS) {
                        return JPEG_CLASS;
                    }
                }
            }
        }
        return distinct == 1 ? first : LOSSLESS_CLASS;
    }
}

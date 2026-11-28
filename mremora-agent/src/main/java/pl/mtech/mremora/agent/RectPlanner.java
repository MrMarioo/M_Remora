package pl.mtech.mremora.agent;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import pl.mtech.mremora.protocol.TileUpdate.Encoding;

/** Classifies dirty tiles and merges horizontal runs of the same class into rectangles. */
final class RectPlanner {

    /** Longest run of tiles merged into one rectangle on the v2 (TCP) path. */
    static final int MAX_MERGE_TILES = 8;
    /**
     * Longest run on the UDP path. Measured with {@code StreamBenchmark#udpRectSizes} at 1600x900 (plan 2, task 10):
     * 8 tiles keep the median rectangle at ≤ 10 fragments (VIDEO; max 12) and need 27 % fewer bytes for the first
     * frame than 4 tiles (6 % fewer for VIDEO), so the v2 width is kept.
     */
    static final int UDP_MAX_MERGE_TILES = 8;
    static final int LOSSLESS_MAX_COLORS = 64;

    /** {@code solidColor} is meaningful only for {@link Encoding#SOLID}. */
    record PlannedRect(int x, int y, int width, int height, Encoding encoding, int solidColor) {
    }

    private RectPlanner() {
    }

    /** Plans with {@link #MAX_MERGE_TILES}. */
    static List<PlannedRect> plan(int[] pixels, int width, int height, BitSet dirty) {
        return plan(pixels, width, height, dirty, MAX_MERGE_TILES);
    }

    /** @param maxMergeTiles longest horizontal run of tiles in one rectangle, at least 1 */
    static List<PlannedRect> plan(int[] pixels, int width, int height, BitSet dirty, int maxMergeTiles) {
        if (maxMergeTiles < 1) {
            throw new IllegalArgumentException("maxMergeTiles must be >= 1: " + maxMergeTiles);
        }
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
                while (end < cols && end - col < maxMergeTiles && dirty.get(row * cols + end)
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

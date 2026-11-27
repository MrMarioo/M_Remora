package pl.mtech.mremora.agent;

import java.util.Arrays;
import java.util.BitSet;

/** Finds the 64x64 tiles that differ between the current stream pixels and the viewer's reference image. */
final class DirtyTiles {

    static final int TILE = 64;

    private DirtyTiles() {
    }

    static int cols(int width) {
        return (width + TILE - 1) / TILE;
    }

    static int rows(int height) {
        return (height + TILE - 1) / TILE;
    }

    /** Bit {@code row * cols + col} is set for each changed tile; a {@code null} reference marks all tiles. */
    static BitSet find(int[] current, int[] reference, int width, int height) {
        int cols = cols(width);
        int rows = rows(height);
        var dirty = new BitSet(cols * rows);
        if (reference == null) {
            dirty.set(0, cols * rows);
            return dirty;
        }
        for (int row = 0; row < rows; row++) {
            int y0 = row * TILE;
            int y1 = Math.min(y0 + TILE, height);
            for (int col = 0; col < cols; col++) {
                int x0 = col * TILE;
                int x1 = Math.min(x0 + TILE, width);
                for (int y = y0; y < y1; y++) {
                    int from = y * width + x0;
                    int to = y * width + x1;
                    if (Arrays.mismatch(current, from, to, reference, from, to) >= 0) {
                        dirty.set(row * cols + col);
                        break;
                    }
                }
            }
        }
        return dirty;
    }
}

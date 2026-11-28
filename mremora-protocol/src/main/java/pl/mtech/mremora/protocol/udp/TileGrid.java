package pl.mtech.mremora.protocol.udp;

import java.util.BitSet;

/**
 * Indexes the 64×64 tiles of a stream of a given size, shared by the agent ({@link TileDelivery}) and the viewer
 * ({@link TileVersions}). Tile index = {@code row * cols + col}; edge tiles of a size that is not a multiple of 64 are
 * smaller. Immutable.
 */
public final class TileGrid {

    /** Tile edge in pixels. */
    public static final int TILE = 64;

    private final int width;
    private final int height;
    private final int cols;
    private final int rows;

    /** Creates the grid of a {@code width}×{@code height} stream (both positive). */
    public TileGrid(int width, int height) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("stream size must be positive: " + width + "x" + height);
        }
        this.width = width;
        this.height = height;
        this.cols = (width + TILE - 1) / TILE;
        this.rows = (height + TILE - 1) / TILE;
    }

    /** Number of tile columns. */
    public int cols() {
        return cols;
    }

    /** Number of tile rows. */
    public int rows() {
        return rows;
    }

    /** Number of tiles. */
    public int count() {
        return cols * rows;
    }

    /**
     * Returns the tiles intersecting the rectangle, clipped to the stream; empty for an empty rectangle or one outside
     * the stream.
     */
    public BitSet tilesOf(int x, int y, int w, int h) {
        BitSet result = new BitSet(count());
        int x0 = Math.max(0, x);
        int y0 = Math.max(0, y);
        int x1 = (int) Math.min(width, (long) x + w);
        int y1 = (int) Math.min(height, (long) y + h);
        if (w <= 0 || h <= 0 || x0 >= x1 || y0 >= y1) {
            return result;
        }
        int c0 = x0 / TILE;
        int c1 = (x1 - 1) / TILE;
        for (int r = y0 / TILE; r <= (y1 - 1) / TILE; r++) {
            result.set(r * cols + c0, r * cols + c1 + 1);
        }
        return result;
    }
}

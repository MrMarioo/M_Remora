package pl.mtech.mremora.protocol.udp;

import java.util.Arrays;
import java.util.BitSet;

/**
 * Viewer side: the {@code captureSeq} last applied to each tile of the current epoch, so that a late rectangle never
 * paints over a newer version of a tile. A new epoch (new stream size) gets a new instance. Not thread-safe (decoder
 * thread).
 */
public final class TileVersions {

    private static final long NEVER = Long.MIN_VALUE;

    private final TileGrid grid;
    private final long[] applied;

    /** Creates versions for {@code grid}, with no tile applied yet. */
    public TileVersions(TileGrid grid) {
        this.grid = grid;
        this.applied = new long[grid.count()];
        Arrays.fill(applied, NEVER);
    }

    /**
     * Returns the tiles of the rectangle whose recorded version is older than {@code captureSeq} and records
     * {@code captureSeq} for them. An empty result means the rectangle carries nothing newer.
     */
    public BitSet claimNewer(int x, int y, int w, int h, long captureSeq) {
        BitSet tiles = grid.tilesOf(x, y, w, h);
        for (int t = tiles.nextSetBit(0); t >= 0; t = tiles.nextSetBit(t + 1)) {
            if (captureSeq > applied[t]) {
                applied[t] = captureSeq;
            } else {
                tiles.clear(t);
            }
        }
        return tiles;
    }
}

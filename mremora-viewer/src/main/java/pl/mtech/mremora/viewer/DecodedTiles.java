package pl.mtech.mremora.viewer;

import java.util.List;

/**
 * One complete frame of tile updates (all parts joined), decoded to pixels. Patches are applied in order.
 * The {@code argb} arrays are handed over from the decoder thread to the UI thread, not copied.
 *
 * @param encodedBytes sum of the rectangle payload sizes of all parts, for bandwidth statistics
 */
record DecodedTiles(long sequence, int streamWidth, int streamHeight, List<Patch> patches, int encodedBytes) {

    /** A {@code width x height} block of ARGB pixels (row by row) to paint at {@code x, y} of the stream image. */
    record Patch(int x, int y, int width, int height, int[] argb) {}
}

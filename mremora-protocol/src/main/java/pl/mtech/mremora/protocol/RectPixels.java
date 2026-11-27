package pl.mtech.mremora.protocol;

import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * Single owner of the {@code SOLID} and {@code LOSSLESS} rectangle payload formats of
 * {@link TileUpdate.Encoding}. Pixels are {@code int} RGB ({@code 0x??RRGGBB}); decoded
 * pixels always carry alpha {@code 0xFF}.
 *
 * <ul>
 *   <li>{@code SOLID}: 4 bytes {@code [0x00, R, G, B]}.</li>
 *   <li>{@code LOSSLESS}: zlib (Deflater, BEST_SPEED) of the pixels row by row, 3 bytes
 *       {@code R, G, B} each; inflating must yield exactly {@code w*h*3} bytes.</li>
 * </ul>
 */
public final class RectPixels {

    private static final int OPAQUE = 0xFF000000;

    private RectPixels() {
    }

    public static byte[] encodeSolid(int rgb) {
        return new byte[]{0, (byte) (rgb >> 16), (byte) (rgb >> 8), (byte) rgb};
    }

    /** @return the colour as ARGB with alpha {@code 0xFF} */
    public static int decodeSolid(byte[] data) {
        if (data.length != 4 || data[0] != 0) {
            throw new IllegalArgumentException("Invalid SOLID payload (length " + data.length + ")");
        }
        return OPAQUE | (data[1] & 0xFF) << 16 | (data[2] & 0xFF) << 8 | (data[3] & 0xFF);
    }

    public static byte[] encodeLossless(int[] pixels, int stride, int x, int y, int w, int h) {
        byte[] raw = new byte[rgbBytes(w, h)];
        int o = 0;
        for (int row = 0; row < h; row++) {
            int p = (y + row) * stride + x;
            for (int col = 0; col < w; col++) {
                int rgb = pixels[p++];
                raw[o++] = (byte) (rgb >> 16);
                raw[o++] = (byte) (rgb >> 8);
                raw[o++] = (byte) rgb;
            }
        }
        Deflater deflater = new Deflater(Deflater.BEST_SPEED);
        try {
            deflater.setInput(raw);
            deflater.finish();
            byte[] buf = new byte[raw.length + raw.length / 100 + 64];
            int n = 0;
            while (!deflater.finished()) {
                if (n == buf.length) {
                    buf = java.util.Arrays.copyOf(buf, buf.length * 2);
                }
                n += deflater.deflate(buf, n, buf.length - n);
            }
            return java.util.Arrays.copyOf(buf, n);
        } finally {
            deflater.end();
        }
    }

    /**
     * @return {@code w*h*3}, the size of the raw RGB rows
     * @throws IllegalArgumentException if {@code w} or {@code h} is negative or the size does not fit an array
     */
    private static int rgbBytes(int w, int h) {
        if (w < 0 || h < 0) {
            throw new IllegalArgumentException("Negative LOSSLESS size %dx%d".formatted(w, h));
        }
        try {
            return Math.multiplyExact(Math.multiplyExact(w, h), 3);
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("LOSSLESS size %dx%d is too large".formatted(w, h), e);
        }
    }

    /** @return {@code w*h} ARGB pixels with alpha {@code 0xFF} */
    public static int[] decodeLossless(byte[] data, int w, int h) {
        int expected = rgbBytes(w, h);
        byte[] raw = new byte[expected];
        Inflater inflater = new Inflater();
        try {
            inflater.setInput(data);
            int n = 0;
            while (n < expected) {
                int got = inflater.inflate(raw, n, expected - n);
                if (got == 0) {
                    if (inflater.finished() || inflater.needsInput() || inflater.needsDictionary()) {
                        break;
                    }
                }
                n += got;
            }
            if (n != expected) {
                throw new IllegalArgumentException("LOSSLESS payload too short: %d of %d bytes".formatted(n, expected));
            }
            // Probe for surplus output with a tiny buffer; never allocate by input size.
            byte[] probe = new byte[1];
            if (inflater.inflate(probe) != 0 || !inflater.finished()) {
                throw new IllegalArgumentException("LOSSLESS payload too long");
            }
        } catch (DataFormatException e) {
            throw new IllegalArgumentException("Invalid LOSSLESS payload: " + e.getMessage(), e);
        } finally {
            inflater.end();
        }
        int[] out = new int[w * h];
        int o = 0;
        for (int i = 0; i < out.length; i++) {
            out[i] = OPAQUE | (raw[o] & 0xFF) << 16 | (raw[o + 1] & 0xFF) << 8 | (raw[o + 2] & 0xFF);
            o += 3;
        }
        return out;
    }
}

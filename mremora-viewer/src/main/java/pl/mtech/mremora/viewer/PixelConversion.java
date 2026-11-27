package pl.mtech.mremora.viewer;

import java.awt.image.BufferedImage;
import java.awt.image.ComponentSampleModel;
import java.awt.image.DataBufferByte;
import java.awt.image.Raster;

/** Image to packed-ARGB conversion shared by the frame and tile decoders. */
final class PixelConversion {

    private PixelConversion() {}

    /**
     * Converts to packed ARGB. JPEGs decode to {@code TYPE_3BYTE_BGR}, which is read straight from its byte buffer;
     * {@link BufferedImage#getRGB} goes through the color model per pixel (~43 ms per 1080p frame) and stays the
     * fallback for every other layout.
     */
    static int[] toArgb(BufferedImage image) {
        int width = image.getWidth();
        int height = image.getHeight();
        if (image.getType() == BufferedImage.TYPE_3BYTE_BGR) {
            int[] argb = fromBgrBytes(image.getRaster(), width, height);
            if (argb != null) {
                return argb;
            }
        }
        return image.getRGB(0, 0, width, height, null, 0, width);
    }

    /** Returns {@code null} when the raster is not the plain interleaved B,G,R byte layout. */
    private static int[] fromBgrBytes(Raster raster, int width, int height) {
        if (!(raster.getSampleModel() instanceof ComponentSampleModel model)
                || !(raster.getDataBuffer() instanceof DataBufferByte buffer)
                || buffer.getNumBanks() != 1
                || model.getNumBands() != 3
                || raster.getSampleModelTranslateX() != 0
                || raster.getSampleModelTranslateY() != 0) {
            return null;
        }
        int[] bandOffsets = model.getBandOffsets(); // index 0 = red, 1 = green, 2 = blue
        int pixelStride = model.getPixelStride();
        int scanlineStride = model.getScanlineStride();
        byte[] data = buffer.getData();
        int base = buffer.getOffset();
        int rOff = bandOffsets[0];
        int gOff = bandOffsets[1];
        int bOff = bandOffsets[2];
        int[] argb = new int[width * height];
        int i = 0;
        for (int y = 0; y < height; y++) {
            int p = base + y * scanlineStride;
            for (int x = 0; x < width; x++, p += pixelStride) {
                argb[i++] = 0xFF000000
                        | (data[p + rOff] & 0xFF) << 16
                        | (data[p + gOff] & 0xFF) << 8
                        | (data[p + bOff] & 0xFF);
            }
        }
        return argb;
    }
}

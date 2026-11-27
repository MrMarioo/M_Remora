package pl.mtech.mremora.agent;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.awt.image.SinglePixelPackedSampleModel;

/** Converts captured frames to RGB pixels at the stream size. */
final class FrameScaler {

    /**
     * @return a fresh array of {@code size.width() * size.height()} RGB pixels, row by row; owned by the caller
     */
    int[] toStreamPixels(BufferedImage capture, StreamSize size) {
        int w = size.width();
        int h = size.height();
        if (capture.getWidth() == w && capture.getHeight() == h) {
            return pixelsOf(capture, w, h);
        }
        var scaled = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = scaled.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(capture, 0, 0, w, h, null);
        } finally {
            g.dispose();
        }
        return pixelsOf(scaled, w, h);
    }

    /** Fresh {@code 0x00RRGGBB} array; plain TYPE_INT_RGB images are read straight from their int buffer. */
    private static int[] pixelsOf(BufferedImage image, int w, int h) {
        var raster = image.getRaster();
        if (image.getType() == BufferedImage.TYPE_INT_RGB
                && raster.getDataBuffer() instanceof DataBufferInt buffer
                && buffer.getNumBanks() == 1
                && buffer.getOffset() == 0
                && raster.getSampleModelTranslateX() == 0
                && raster.getSampleModelTranslateY() == 0
                && raster.getSampleModel() instanceof SinglePixelPackedSampleModel model
                && model.getScanlineStride() == w
                && buffer.getSize() >= w * h) {
            int[] src = buffer.getData();
            int[] out = new int[w * h];
            for (int i = 0; i < out.length; i++) {
                out[i] = src[i] & 0xFFFFFF;
            }
            return out;
        }
        return opaqueRgb(image.getRGB(0, 0, w, h, null, 0, w));
    }

    /** Drops the alpha byte that {@code getRGB} adds, leaving {@code 0x00RRGGBB}. */
    private static int[] opaqueRgb(int[] argb) {
        for (int i = 0; i < argb.length; i++) {
            argb[i] &= 0xFFFFFF;
        }
        return argb;
    }
}

package pl.mtech.mremora.agent;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;

/** Encodes captured screen frames as JPEG. */
final class JpegFrameEncoder {

    static final float DEFAULT_QUALITY = 0.7f;

    private final float quality;

    /**
     * @param quality JPEG quality in {@code [0, 1]}
     * @throws IllegalArgumentException if quality is outside that range
     */
    JpegFrameEncoder(float quality) {
        if (!(quality >= 0f && quality <= 1f)) {
            throw new IllegalArgumentException("quality must be in [0, 1], was " + quality);
        }
        this.quality = quality;
    }

    byte[] encode(BufferedImage image) {
        BufferedImage rgb = toRgb(image);
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        try {
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(quality);
            var out = new ByteArrayOutputStream();
            try (ImageOutputStream ios = new MemoryCacheImageOutputStream(out)) {
                writer.setOutput(ios);
                writer.write(null, new IIOImage(rgb, null, null), param);
            }
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            writer.dispose();
        }
    }

    /** The JPEG writer rejects images with alpha, so anything but plain RGB is redrawn. */
    private static BufferedImage toRgb(BufferedImage image) {
        int type = image.getType();
        if (type == BufferedImage.TYPE_INT_RGB || type == BufferedImage.TYPE_3BYTE_BGR) {
            return image;
        }
        var copy = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
        var g = copy.createGraphics();
        try {
            g.drawImage(image, 0, 0, null);
        } finally {
            g.dispose();
        }
        return copy;
    }
}

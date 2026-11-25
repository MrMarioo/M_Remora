package pl.mtech.mremora.viewer;

import java.util.Optional;

/**
 * The rectangle, in view coordinates, in which the remote screen image is drawn, and the mapping from view
 * coordinates back to agent screen pixels.
 *
 * <p>The image keeps its aspect ratio and is centered (letterbox / pillarbox); it is scaled both down and up.
 */
record Viewport(int imageWidth, int imageHeight, double x, double y, double width, double height) {

    /**
     * Fits an image of the given size into a view, preserving proportions and centering it. A view that is not
     * positive in either dimension yields an empty viewport.
     */
    static Viewport fit(int imageWidth, int imageHeight, double viewWidth, double viewHeight) {
        if (viewWidth <= 0 || viewHeight <= 0 || imageWidth <= 0 || imageHeight <= 0) {
            return new Viewport(imageWidth, imageHeight, 0, 0, 0, 0);
        }
        double scale = Math.min(viewWidth / imageWidth, viewHeight / imageHeight);
        double w = imageWidth * scale;
        double h = imageHeight * scale;
        return new Viewport(imageWidth, imageHeight, (viewWidth - w) / 2, (viewHeight - h) / 2, w, h);
    }

    boolean isEmpty() {
        return width <= 0 || height <= 0;
    }

    /** Maps a view point to an image pixel, or empty when it lies outside the drawn image or the viewport is empty. */
    Optional<ImagePoint> toImage(double vx, double vy) {
        if (isEmpty() || vx < x || vx >= x + width || vy < y || vy >= y + height) {
            return Optional.empty();
        }
        return Optional.of(map(vx, vy));
    }

    /**
     * Maps a view point to the nearest image pixel, clamping points outside the image to its edges.
     *
     * @throws IllegalStateException when the viewport is empty
     */
    ImagePoint toImageClamped(double vx, double vy) {
        if (isEmpty()) {
            throw new IllegalStateException("Viewport is empty");
        }
        return map(vx, vy);
    }

    private ImagePoint map(double vx, double vy) {
        int px = (int) Math.floor((vx - x) / width * imageWidth);
        int py = (int) Math.floor((vy - y) / height * imageHeight);
        return new ImagePoint(clamp(px, imageWidth - 1), clamp(py, imageHeight - 1));
    }

    private static int clamp(int value, int max) {
        return Math.max(0, Math.min(value, max));
    }
}

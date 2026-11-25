package pl.mtech.mremora.protocol;

/**
 * Agent screen geometry in physical pixels, plus the OS scaling factor (100 = no scaling).
 */
public record ScreenInfo(int width, int height, int scalePercent) implements Message {

    public ScreenInfo {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("Screen size must be positive, was %dx%d".formatted(width, height));
        }
        if (scalePercent <= 0) {
            throw new IllegalArgumentException("scalePercent must be positive, was " + scalePercent);
        }
    }
}

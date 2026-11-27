package pl.mtech.mremora.protocol;

/**
 * Viewer to agent: the largest picture the viewer wants. {@code 0, 0} means full resolution;
 * otherwise both values are at least 1.
 */
public record StreamConfig(int maxWidth, int maxHeight) implements Message {

    public static final StreamConfig FULL_RESOLUTION = new StreamConfig(0, 0);

    public StreamConfig {
        if (maxWidth < 0 || maxWidth > 0xFFFF || maxHeight < 0 || maxHeight > 0xFFFF) {
            throw new IllegalArgumentException("Stream size out of u16 range: %dx%d".formatted(maxWidth, maxHeight));
        }
        if ((maxWidth == 0) != (maxHeight == 0)) {
            throw new IllegalArgumentException(
                    "Stream size must be 0x0 or have both sides positive: %dx%d".formatted(maxWidth, maxHeight));
        }
    }
}

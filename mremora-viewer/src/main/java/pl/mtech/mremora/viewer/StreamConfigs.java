package pl.mtech.mremora.viewer;

import pl.mtech.mremora.protocol.ScreenInfo;
import pl.mtech.mremora.protocol.StreamConfig;

/** Computes the {@link StreamConfig} a viewer asks the agent for, from its view size. JavaFX-free. */
final class StreamConfigs {

    /** Guards {@code ceil} against floating point noise such as 915.0000000001. */
    private static final double EPSILON = 1e-6;

    private StreamConfigs() {}

    /**
     * The stream size that just covers the area the image occupies in the view (times {@code outputScale}).
     *
     * @return the config, {@link StreamConfig#FULL_RESOLUTION} when the view needs the whole screen or more, or
     *         {@code null} when the view is empty (nothing to send)
     */
    static StreamConfig forView(ScreenInfo screen, double viewWidth, double viewHeight, double outputScale) {
        Viewport fit = Viewport.fit(screen.width(), screen.height(), viewWidth, viewHeight);
        if (fit.isEmpty() || outputScale <= 0) {
            return null;
        }
        int w = (int) Math.ceil(fit.width() * outputScale - EPSILON);
        int h = (int) Math.ceil(fit.height() * outputScale - EPSILON);
        if (w >= screen.width() && h >= screen.height()) {
            return StreamConfig.FULL_RESOLUTION;
        }
        return new StreamConfig(Math.max(1, Math.min(w, screen.width())), Math.max(1, Math.min(h, screen.height())));
    }
}

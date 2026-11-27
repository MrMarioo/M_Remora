package pl.mtech.mremora.agent;

import pl.mtech.mremora.protocol.StreamConfig;

/** Resolution of the pixel stream sent to the viewer. */
record StreamSize(int width, int height) {

    StreamSize {
        if (width < 1 || height < 1) {
            throw new IllegalArgumentException("size must be positive: " + width + "x" + height);
        }
    }

    /**
     * Fits the screen into the viewer's limits, keeping the aspect ratio. Never upscales; a limit of 0 means
     * unlimited on that axis.
     */
    static StreamSize fit(int screenWidth, int screenHeight, StreamConfig config) {
        long maxW = config.maxWidth() <= 0 ? Long.MAX_VALUE : config.maxWidth();
        long maxH = config.maxHeight() <= 0 ? Long.MAX_VALUE : config.maxHeight();
        if (maxW >= screenWidth && maxH >= screenHeight) {
            return new StreamSize(screenWidth, screenHeight);
        }
        // Compare maxW/screenW with maxH/screenH without floating point; the smaller ratio wins.
        if (maxW * screenHeight <= maxH * screenWidth) {
            return new StreamSize((int) maxW, (int) Math.max(1, screenHeight * maxW / screenWidth));
        }
        return new StreamSize((int) Math.max(1, screenWidth * maxH / screenHeight), (int) maxH);
    }
}

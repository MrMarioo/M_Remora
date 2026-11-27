package pl.mtech.mremora.agent;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.Arrays;
import java.util.Random;

/** Deterministic desktop-like frame generator: gradient + noise background with text-like windows. */
final class SyntheticScreen {

    private static final int LIGHT = 0xF0F0F0;
    private static final int DARK = 0x202020;
    private static final int CHAR_W = 8;
    private static final int CHAR_H = 14;
    private static final int SCROLL_STEP = 40;

    private final Scenario scenario;
    private final int width;
    private final int height;
    private final long seed;
    private final int[] background;
    private final Random videoRandom;
    private int frameIndex;
    private int[] typingCanvas;

    SyntheticScreen(Scenario scenario, int width, int height, long seed) {
        this.scenario = scenario;
        this.width = width;
        this.height = height;
        this.seed = seed;
        this.background = new int[width * height];
        var rnd = new Random(seed);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int r = 40 + 60 * x / width + rnd.nextInt(6);
                int g = 60 + 80 * y / height + rnd.nextInt(6);
                int b = 120 + 60 * (x + y) / (width + height) + rnd.nextInt(6);
                background[y * width + x] = r << 16 | g << 8 | b;
            }
        }
        this.videoRandom = new Random(seed ^ 0x5DEECE66DL);
    }

    BufferedImage next() {
        int k = frameIndex++;
        int[] px = switch (scenario) {
            case STATIC -> staticFrame();
            case TYPING -> typingFrame(k);
            case WINDOW_DRAG -> dragFrame(k);
            case SCROLL -> scrollFrame(k);
            case VIDEO -> videoFrame();
        };
        var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        int[] target = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
        System.arraycopy(px, 0, target, 0, px.length);
        return image;
    }

    private int[] staticFrame() {
        int[] px = background.clone();
        drawTextWindow(px, 0, 0, 800, 600, 0); // same window as WINDOW_DRAG's first frame
        return px;
    }

    private int[] typingFrame(int k) {
        if (typingCanvas == null) {
            typingCanvas = background.clone();
            fillRect(typingCanvas, 100, 100, 800, 600, LIGHT);
        }
        int cols = 800 / CHAR_W - 2;
        int rows = 600 / (CHAR_H + 4) - 1;
        int cell = k % (cols * rows);
        if (cell == 0 && k > 0) {
            fillRect(typingCanvas, 100, 100, 800, 600, LIGHT);
        }
        fillRect(typingCanvas, 108 + (cell % cols) * CHAR_W, 108 + (cell / cols) * (CHAR_H + 4), CHAR_W, CHAR_H, DARK);
        return typingCanvas.clone();
    }

    private int[] dragFrame(int k) {
        int[] px = background.clone();
        drawTextWindow(px, Math.floorMod(12 * k, width), Math.floorMod(6 * k, height), 800, 600, 0);
        return px;
    }

    private int[] scrollFrame(int k) {
        int[] px = background.clone();
        drawTextWindow(px, 100, 100, 1200, 900, k * SCROLL_STEP);
        return px;
    }

    private int[] videoFrame() {
        int[] px = background.clone();
        for (int y = 100; y < Math.min(height, 100 + 720); y++) {
            for (int x = 100; x < Math.min(width, 100 + 1280); x++) {
                px[y * width + x] = videoRandom.nextInt(0x1000000);
            }
        }
        return px;
    }

    /**
     * Light window with "text": 1-2 px dark horizontal stripes in 40 px cells whose length depends on the
     * absolute row ({@code scrollOffset + y}), so scrolling by 40 px shifts content by whole cells.
     */
    private void drawTextWindow(int[] px, int x0, int y0, int w, int h, int scrollOffset) {
        for (int dy = 0; dy < h; dy++) {
            int y = y0 + dy;
            if (y < 0 || y >= height) {
                continue;
            }
            int row = dy + scrollOffset;
            int cell = Math.floorDiv(row, SCROLL_STEP);
            int within = Math.floorMod(row, SCROLL_STEP);
            int stripeLen = 0;
            if (within >= 8 && within < 10 || within >= 18 && within < 19 || within >= 28 && within < 30) {
                int hash = (int) ((cell * 0x9E3779B97F4A7C15L + within / 10 * 31L + seed * 0xBF58476D1CE4E5B9L) >>> 40);
                stripeLen = (40 + Math.floorMod(hash, w / 3)) & ~1;
            }
            int xStart = Math.max(0, x0);
            int xEnd = Math.min(width, x0 + w);
            for (int x = xStart; x < xEnd; x++) {
                boolean dark = stripeLen > 0 && x >= x0 + 10 && x < x0 + 10 + stripeLen && ((x - x0) & 7) < 6;
                px[y * width + x] = dark ? DARK : LIGHT;
            }
        }
    }

    private void fillRect(int[] px, int x0, int y0, int w, int h, int color) {
        for (int y = Math.max(0, y0); y < Math.min(height, y0 + h); y++) {
            int from = y * width + Math.max(0, x0);
            int to = y * width + Math.min(width, x0 + w);
            if (to > from) {
                Arrays.fill(px, from, to, color);
            }
        }
    }
}

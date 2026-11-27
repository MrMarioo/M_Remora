package pl.mtech.mremora.viewer;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.image.BufferedImage;
import java.time.Duration;
import java.time.Instant;
import java.util.Random;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.IntBinaryOperator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import pl.mtech.mremora.agent.AgentConfig;
import pl.mtech.mremora.agent.AgentServer;
import pl.mtech.mremora.agent.PairingCodes;
import pl.mtech.mremora.testkit.MutableClock;

/**
 * The v2 tile stream end to end, pixel exact: a real {@link AgentServer} (tile encoder, codec) over loopback into a
 * {@link ViewerSession} ({@link TileDecoder}), whose patches are applied to a composite image.
 *
 * <p>The content is lossless only (flat rectangles and 2-colour stripes, far below 64 colours per tile) and constant
 * over 2x2 blocks with even edges, so the agent's 2:1 downscale is exact and the expected stream pixels do not
 * depend on the scaling algorithm. The screen size is not a multiple of the 64 px tile, so edge tiles are covered.
 * Each change is made only after the previous frame arrived, so every frame shows exactly one source image.
 */
class TileStreamEndToEndTest {

    private static final Duration WAIT = Duration.ofSeconds(5);
    private static final int W = 200;
    private static final int H = 136;

    private final MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
    private final RecordingAgentUi ui = new RecordingAgentUi();
    private final FakeDesktop desktop = new FakeDesktop(W, H);
    private final CompositingListener listener = new CompositingListener();
    private AgentServer server;
    private ViewerSession session;

    @AfterEach
    void tearDown() throws Exception {
        try {
            if (session != null) {
                session.disconnect().get(WAIT.toMillis(), TimeUnit.MILLISECONDS);
            }
        } finally {
            if (server != null) {
                server.close();
            }
        }
    }

    @Test
    void compositeEqualsTheAgentStreamAfterEveryFrame() throws Exception {
        IntBinaryOperator a = (x, y) -> {
            if (x >= 10 && x < 50 && y >= 20 && y < 60) {
                return 0xAA0000;
            }
            if (x >= 100 && y < 40) { // vertical stripes into the right edge tile
                return (x / 2) % 2 == 0 ? 0x00FF00 : 0x0000FF;
            }
            return 0x203040;
        };
        IntBinaryOperator b = (x, y) -> x >= 180 && y >= 120 ? 0xFFFF00 : a.applyAsInt(x, y); // corner edge tile
        IntBinaryOperator c = (x, y) -> x < 64 && y >= 64 && y < 128
                ? ((y / 2) % 2 == 0 ? 0xFFFFFF : 0x000000) // horizontal stripes
                : b.applyAsInt(x, y);
        IntBinaryOperator d = (x, y) -> x >= 150 && y >= 100 ? 0x123456 : c.applyAsInt(x, y);

        desktop.setImage(image(a));
        AgentConfig defaults = AgentConfig.defaults(0);
        server = new AgentServer(new AgentConfig(0, defaults.handshakeTimeout(), defaults.heartbeatInterval(),
                defaults.idleTimeout(), defaults.maxFps(), 2), desktop, desktop,
                new PairingCodes(clock, new Random(1)), ui);
        int port = server.start();
        session = ViewerSession.open("127.0.0.1", port, ui.lastCode(), 2, Runnable::run, listener)
                .get(WAIT.toMillis(), TimeUnit.MILLISECONDS);
        assertThat(session.protocolVersion()).isEqualTo(2);

        expectFrame(W, H, a, 1);

        desktop.setImage(image(b));
        expectFrame(W, H, b, 1);

        desktop.setImage(image(c));
        expectFrame(W, H, c, 1);

        session.viewChanged(W / 2, H / 2, 1); // StreamConfig(100, 68): a full frame at half size
        expectFrame(W / 2, H / 2, c, 2);

        desktop.setImage(image(d)); // changes the right and bottom edge tiles of the 100x68 stream
        expectFrame(W / 2, H / 2, d, 2);

        session.viewChanged(W, H, 1); // back to full resolution
        expectFrame(W, H, d, 1);
    }

    /** Waits for the next frame and checks the composite against {@code source} sampled every {@code step} px. */
    private void expectFrame(int width, int height, IntBinaryOperator source, int step) throws InterruptedException {
        Composite frame = listener.frames.poll(WAIT.toMillis(), TimeUnit.MILLISECONDS);
        assertThat(frame).as("frame within %s", WAIT).isNotNull();
        assertThat(frame.width).isEqualTo(width);
        assertThat(frame.height).isEqualTo(height);
        int[] expected = new int[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                expected[y * width + x] = 0xFF000000 | source.applyAsInt(x * step, y * step);
            }
        }
        assertThat(frame.argb).isEqualTo(expected);
    }

    private static BufferedImage image(IntBinaryOperator source) {
        BufferedImage image = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                image.setRGB(x, y, source.applyAsInt(x, y));
            }
        }
        return image;
    }

    private record Composite(int width, int height, int[] argb) {}

    /**
     * Applies each frame's patches to a composite, as the session view does: a new stream size starts from an empty
     * (transparent) image, so a frame that does not cover it all fails the comparison. Called on the decoder thread.
     */
    private static final class CompositingListener implements ViewerSession.Listener {

        final BlockingQueue<Composite> frames = new LinkedBlockingQueue<>();
        private int width;
        private int height;
        private int[] composite;

        @Override
        public void onFrame(DecodedFrame frame) {
            throw new AssertionError("v1 frame on a v2 session");
        }

        @Override
        public void onTiles(DecodedTiles tiles) {
            if (composite == null || tiles.streamWidth() != width || tiles.streamHeight() != height) {
                width = tiles.streamWidth();
                height = tiles.streamHeight();
                composite = new int[width * height];
            }
            for (DecodedTiles.Patch p : tiles.patches()) {
                for (int row = 0; row < p.height(); row++) {
                    System.arraycopy(p.argb(), row * p.width(), composite, (p.y() + row) * width + p.x(), p.width());
                }
            }
            frames.add(new Composite(width, height, composite.clone()));
        }

        @Override
        public void onClosed(String reason) {
        }
    }
}

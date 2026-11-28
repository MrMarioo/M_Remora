package pl.mtech.mremora.viewer;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import pl.mtech.mremora.agent.AgentConfig;
import pl.mtech.mremora.agent.AgentServer;
import pl.mtech.mremora.agent.PairingCodes;
import pl.mtech.mremora.agent.UdpImageStats;
import pl.mtech.mremora.protocol.TransportState;
import pl.mtech.mremora.protocol.netty.LinkImpairment;
import pl.mtech.mremora.testkit.MutableClock;

/**
 * The picture over UDP (protocol v3) end to end on real sockets, pixel exact: a real {@link AgentServer} whose UDP
 * socket is impaired ({@link AgentServer#udpImpairment}: loss, duplicates, delay, jitter, bandwidth, both directions)
 * streams into a real {@link ViewerSession}; the test applies the decoded batches to one composite image and waits
 * until it equals the agent's screen.
 *
 * <p>Content: noise of 4x4 px blocks, each a colour of one fixed 64-colour palette. At most 64 colours per tile keeps
 * every tile LOSSLESS (so the result can be pixel exact), and the noise compresses poorly enough that rectangles span
 * several fragments. The blocks have even edges, so the agent's 2:1 downscale (bilinear) only averages equal pixels
 * and the half-size stream equals {@code source(2x, 2y)}. The screen is not a multiple of the 64 px tile.
 *
 * <p>Sizing: a full frame of {@link #W}x{@link #H} in 4x4 blocks encodes to about {@value #FRAME_KB} KB (measured,
 * printed by {@link #udpPixelExactNoLoss}). With 10 ms jitter packets reorder by more than three, so the sender may
 * see constant loss and sit at its floor of 0.5 Mbit/s, about {@value #FLOOR_KB_PER_S} KB/s; each timeout is derived
 * from that rate (see {@link #LOSSY_FULL_FRAME} and {@link #twoMbitSessionConverges}).
 */
class UdpImageEndToEndTest {

    private static final Duration WAIT = Duration.ofSeconds(5);
    private static final int W = 800;
    private static final int H = 600;
    /** Encoded full frame in 4x4 blocks, rounded up (93 fragments of at most 1134 B in the no-loss run). */
    private static final int FRAME_KB = 110;
    /** Encoded full frame in 2x2 blocks, rounded up (297 fragments in the 2 Mbit/s run). */
    private static final int FINE_FRAME_KB = 340;
    /** Packets sent per fragment of content under 5% loss, rounded up from the measured 1.8-2.1. */
    private static final double LOSSY_SEND_FACTOR = 2.5;
    /** 0.5 Mbit/s, the sender's lowest rate. */
    private static final int FLOOR_KB_PER_S = 60;
    /**
     * A full 4x4 frame under 5% loss: at the floor rate {@code FRAME_KB / FLOOR_KB_PER_S} s, times
     * {@value #LOSSY_SEND_FACTOR} for resent rectangles (measured: the loss runs sent 1.8-2.1 packets per fragment of
     * content, as a lost fragment resends its whole rectangle and reordering counts as loss), plus
     * {@code CONFIRM_TIMEOUT} (2 s) for a rectangle lost without a report and 1 s slack: about 7.6 s.
     */
    private static final Duration LOSSY_FULL_FRAME =
            Duration.ofMillis((long) (FRAME_KB * 1000.0 / FLOOR_KB_PER_S * LOSSY_SEND_FACTOR) + 2000 + 1000);
    /** Full-screen changes, one every 100 ms, in the 2 Mbit/s scenario. */
    private static final int CHANGES = 40;
    private static final int[] PALETTE = palette();

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
    void udpPixelExactNoLoss() throws Exception {
        int[] a = noise(1, 4);
        connect(LinkImpairment.NONE, a);
        expect(W, H, a, 1, WAIT);
        printStats("no loss, first frame");

        int[] b = withRegion(a, 100, 100, 300, 200, 2, 4);
        show(b);
        expect(W, H, b, 1, WAIT);
        assertUdp("no loss");
        assertIdleIsQuiet();
    }

    /**
     * On a still screen the agent sends (almost) nothing: at most 2 {@code TILE}s (a last capture racing the start of
     * the window), and about 3 datagrams a second in all - its NAT keepalive probe and the replies to the viewer's
     * (one each per {@code PROBE_SLOW} = 1 s), plus slack. The allowance is scaled by the measured window.
     */
    private void assertIdleIsQuiet() throws InterruptedException {
        UdpImageStats before = server.udpImageStats().orElseThrow();
        long started = System.nanoTime();
        Thread.sleep(3000);
        UdpImageStats after = server.udpImageStats().orElseThrow();
        double seconds = (System.nanoTime() - started) / 1e9;
        long tiles = after.packetsSent() - before.packetsSent();
        long datagrams = after.datagramsSent() - before.datagramsSent();
        System.out.printf(Locale.ROOT, "UdpImageEndToEndTest [idle]: %.2f s, %d TILE, %d datagrams%n", seconds, tiles,
                datagrams);
        assertThat(tiles).as("TILE packets on a still screen").isLessThanOrEqualTo(2);
        assertThat(datagrams).as("datagrams in %.2f s on a still screen", seconds)
                .isLessThanOrEqualTo((long) Math.ceil(3 * seconds));
    }

    @Test
    void udpPixelExactWithFivePercentLoss() throws Exception {
        int[] a = noise(1, 4);
        connect(lossy(0), a);
        expect(W, H, a, 1, LOSSY_FULL_FRAME);

        int[] b = withRegion(a, 100, 100, 300, 200, 2, 4);
        showAndAwaitSending(b);
        int[] c = withRegion(b, 200, 200, 400, 300, 3, 4); // while b is in flight: their tiles overlap
        show(c);
        expect(W, H, c, 1, LOSSY_FULL_FRAME);
        UdpImageStats stats = assertUdp("5% loss");
        assertThat(stats.injectorDrops()).as("injector dropped datagrams").isPositive();
        assertThat(stats.packetsLost()).as("sender detected losses").isPositive();
    }

    @Test
    void resizeUnderLossConverges() throws Exception {
        int[] a = noise(1, 4);
        connect(lossy(0), a);
        expect(W, H, a, 1, LOSSY_FULL_FRAME);

        int[] b = withRegion(a, 0, 0, 400, 300, 2, 4);
        showAndAwaitSending(b);
        session.viewChanged(W / 2, H / 2, 1); // StreamConfig(W/2, H/2) while b is in flight: a new epoch
        expect(W / 2, H / 2, b, 2, LOSSY_FULL_FRAME); // a quarter of the bytes; same budget

        int[] c = withRegion(b, 400, 300, 400, 300, 3, 4);
        showAndAwaitSending(c);
        session.viewChanged(W, H, 1); // back to full resolution, again with a change in flight
        expect(W, H, c, 1, LOSSY_FULL_FRAME);
        UdpImageStats stats = assertUdp("resize, 5% loss");
        assertThat(stats.injectorDrops()).as("injector dropped datagrams").isPositive();
        assertThat(stats.packetsLost()).as("sender detected losses").isPositive();
    }

    /**
     * 2x2 blocks (about {@value #FINE_FRAME_KB} KB a frame, see the class description), so a burst of one capture
     * overruns the 64 KiB router queue of the 2 Mbit/s link even when paced at little more than 2 Mbit/s. Budget: the
     * last frame at the 0.5 Mbit/s floor, times {@value #LOSSY_SEND_FACTOR} for resent rectangles (queue overflow
     * loses packets like random loss does), plus {@code CONFIRM_TIMEOUT} (2 s) and 1 s slack:
     * {@code 340 / 60 x 2.5 + 3 = 17.2 s}, inside {@code WAIT x 4} (20 s).
     */
    @Test
    void twoMbitSessionConverges() throws Exception {
        int[] screen = noise(1, 2);
        connect(new LinkImpairment(0, 0, Duration.ofMillis(10), Duration.ZERO, false, false, 7, 2_000_000), screen);
        expect(W, H, screen, 1, WAIT.multipliedBy(4));
        printStats("2 Mbit/s, first frame");
        var rates = new StringBuilder();
        double lastHalf = 0;
        double min = Double.MAX_VALUE;
        double max = 0;
        for (int i = 0; i < CHANGES; i++) { // the whole screen every 100 ms: far more than the link carries
            Thread.sleep(100);
            screen = noise(10 + i, 2);
            show(screen);
            double r = rate();
            rates.append(String.format(Locale.ROOT, " %.2f", r));
            if (i >= CHANGES / 2) { // the last 2 s of load: the AIMD sawtooth around the link rate
                lastHalf += r / (CHANGES - CHANGES / 2);
                min = Math.min(min, r);
                max = Math.max(max, r);
            }
        }
        expect(W, H, screen, 1, WAIT.multipliedBy(4));
        System.out.printf(Locale.ROOT, "UdpImageEndToEndTest [2 Mbit/s]: rate under load (Mbit/s, every 100 ms):%s;"
                + " last %d: min %.2f, max %.2f, mean %.2f%n", rates, CHANGES - CHANGES / 2, min, max, lastHalf);
        UdpImageStats stats = assertUdp("2 Mbit/s");
        assertThat(stats.injectorDrops()).as("router queue overflowed").isPositive();
        // The rate is a sawtooth (peaks near 4 before each halving), so its mean under load is checked, not one
        // sample. Measured mean 2.20-2.36 Mbit/s; the bound leaves CI margin and is still far below the 5 Mbit/s start.
        assertThat(lastHalf).as("mean pacer rate (Mbit/s) over the last 2 s of load").isLessThan(3.0);
    }

    @Test
    void blockedUdpStillV2Pixels() throws Exception {
        int[] a = noise(1, 4);
        connect(LinkImpairment.blocked(), a);
        expect(W, H, a, 1, WAIT);
        assertThat(server.lastUdpOutcome()).contains(TransportState.Mode.TCP);

        int[] b = withRegion(a, 100, 100, 300, 200, 2, 4);
        show(b);
        expect(W, H, b, 1, WAIT);
        assertThat(server.udpImageStats().orElseThrow().packetsSent()).as("nothing over UDP").isZero();
        assertThat(listener.closedReason()).as("session still open").isNull();
    }

    private static LinkImpairment lossy(long seed) {
        return new LinkImpairment(0.05, 0.01, Duration.ofMillis(20), Duration.ofMillis(10), false, false, seed);
    }

    /** Starts the agent showing {@code first} (so its first capture is already the noise), then connects. */
    private void connect(LinkImpairment impairment, int[] first) throws Exception {
        show(first);
        AgentConfig defaults = AgentConfig.defaults(0);
        server = new AgentServer(new AgentConfig(0, defaults.handshakeTimeout(), defaults.heartbeatInterval(),
                defaults.idleTimeout(), defaults.maxFps(), 3), desktop, desktop,
                new PairingCodes(clock, new Random(1)), ui);
        server.udpImpairment(impairment);
        server.udpStartupTimeout(Duration.ofMillis(500));
        int port = server.start();
        session = ViewerSession.open("127.0.0.1", port, ui.lastCode(), 3, Runnable::run, listener)
                .get(WAIT.toMillis(), TimeUnit.MILLISECONDS);
        assertThat(session.protocolVersion()).isEqualTo(3);
    }

    private double rate() {
        return server.udpImageStats().orElseThrow().rateBitsPerSecond() / 1e6;
    }

    private UdpImageStats printStats(String scenario) {
        UdpImageStats stats = server.udpImageStats().orElseThrow();
        System.out.printf(Locale.ROOT, "UdpImageEndToEndTest [%s]: %s, settled rate %.2f Mbit/s%n",
                scenario, stats, stats.rateBitsPerSecond() / 1e6);
        return stats;
    }

    /** Checks the picture went over UDP and prints the agent's statistics. */
    private UdpImageStats assertUdp(String scenario) {
        UdpImageStats stats = printStats(scenario);
        assertThat(server.lastUdpOutcome()).contains(TransportState.Mode.UDP);
        assertThat(stats.packetsSent()).as("picture went over UDP").isPositive();
        assertThat(stats.fragmentsSent()).as("rectangles spanned several fragments").isGreaterThan(stats.rectsSent());
        assertThat(listener.closedReason()).as("session still open").isNull();
        return stats;
    }

    private void show(int[] pixels) {
        var image = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        System.arraycopy(pixels, 0, ((DataBufferInt) image.getRaster().getDataBuffer()).getData(), 0, pixels.length);
        desktop.setImage(image);
    }

    /** Shows {@code pixels} and waits until the agent sent a packet more, i.e. the change is in flight. */
    private void showAndAwaitSending(int[] pixels) throws InterruptedException {
        long before = server.udpImageStats().orElseThrow().packetsSent();
        show(pixels);
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (server.udpImageStats().orElseThrow().packetsSent() <= before) {
            assertThat(deadline - System.nanoTime()).as("change sent within %s", WAIT).isPositive();
            Thread.sleep(1);
        }
    }

    /** Waits until the composite shows {@code source} sampled every {@code step} px at the given stream size. */
    private void expect(int width, int height, int[] source, int step, Duration timeout) throws InterruptedException {
        int[] expected = new int[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                expected[y * width + x] = 0xFF000000 | source[y * step * W + x * step];
            }
        }
        Snapshot last = listener.await(width, height, expected, timeout);
        assertThat(last).as("any picture within %s", timeout).isNotNull();
        assertThat(last.width()).isEqualTo(width);
        assertThat(last.height()).isEqualTo(height);
        int mismatched = 0;
        for (int i = 0; i < expected.length; i++) {
            if (last.argb()[i] != expected[i]) {
                mismatched++;
            }
        }
        assertThat(mismatched).as("pixels differing from the agent's screen after %s", timeout).isZero();
    }

    /** 64 distinct colours, fixed. */
    private static int[] palette() {
        var rnd = new Random(64);
        int[] p = new int[64];
        for (int i = 0; i < p.length; i++) {
            p[i] = (i * 4) << 16 | rnd.nextInt(256) << 8 | rnd.nextInt(256); // distinct red channel
        }
        return p;
    }

    /** Full-screen noise of {@code block}x{@code block} px blocks from {@link #PALETTE}. */
    private static int[] noise(int seed, int block) {
        return withRegion(new int[W * H], 0, 0, W, H, seed, block);
    }

    /** A copy of {@code base} with the block-aligned region redrawn as noise of {@code seed}. */
    private static int[] withRegion(int[] base, int x0, int y0, int w, int h, int seed, int block) {
        int[] px = base.clone();
        for (int y = y0; y < Math.min(H, y0 + h); y++) {
            for (int x = x0; x < Math.min(W, x0 + w); x++) {
                long hash = (x / block * 0x9E3779B97F4A7C15L) ^ (y / block * 0xBF58476D1CE4E5B9L)
                        ^ (seed * 0x94D049BB133111EBL);
                hash ^= hash >>> 31;
                hash *= 0xD6E8FEB86659FD93L;
                hash ^= hash >>> 32;
                px[y * W + x] = PALETTE[(int) (hash & 63)];
            }
        }
        return px;
    }

    private record Snapshot(int width, int height, int[] argb) {}

    /**
     * Applies each batch's patches to one composite, as the session view does: a new stream size starts from an
     * empty (transparent) image. Called on the decoder thread; {@link #await} compares on the test thread.
     */
    private static final class CompositingListener implements ViewerSession.Listener {

        private int width;
        private int height;
        private int[] composite;
        private String closedReason;

        @Override
        public void onFrame(DecodedFrame frame) {
            throw new AssertionError("v1 frame on a v3 session");
        }

        @Override
        public synchronized void onTiles(DecodedTiles tiles) {
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
            notifyAll();
        }

        @Override
        public synchronized void onClosed(String reason) {
            closedReason = reason;
            notifyAll();
        }

        synchronized String closedReason() {
            return closedReason;
        }

        /** Until the composite equals {@code expected} or {@code timeout} passes; the last composite, if any. */
        synchronized Snapshot await(int w, int h, int[] expected, Duration timeout) throws InterruptedException {
            long deadline = System.nanoTime() + timeout.toNanos();
            while (true) {
                if (composite != null && width == w && height == h && Arrays.equals(composite, expected)) {
                    break;
                }
                long left = deadline - System.nanoTime();
                if (left <= 0 || closedReason != null) {
                    break;
                }
                TimeUnit.NANOSECONDS.timedWait(this, left);
            }
            return composite == null ? null : new Snapshot(width, height, composite.clone());
        }
    }
}

package pl.mtech.mremora.protocol.udp;

import static org.assertj.core.api.Assertions.assertThat;
import static pl.mtech.mremora.protocol.udp.SimulatedLink.MS;
import static pl.mtech.mremora.protocol.udp.SimulatedLink.SECOND;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import pl.mtech.mremora.protocol.udp.SimulatedLink.Config;

/**
 * Phase-1 gate of the image stream over UDP: on a simulated link, after the agent's screen stops changing, the
 * viewer's tile versions equal the agent's and nothing is left in flight. Every scenario runs on seeds 1..20.
 */
class SimulatedLinkTest {

    private static final int SEEDS = 20;

    private static List<SimulatedLink> runAll(Consumer<Config> scenario) {
        List<SimulatedLink> runs = new ArrayList<>();
        for (long seed = 1; seed <= SEEDS; seed++) {
            long s = seed;
            Config c = new Config().with(scenario).with(x -> x.seed = s);
            SimulatedLink sim = new SimulatedLink(c).run();
            assertThat(sim.converged()).as(sim.describe()).isTrue();
            runs.add(sim);
        }
        return runs;
    }

    @Test
    void simNoLoss() {
        for (SimulatedLink sim : runAll(c -> {})) {
            assertThat(sim.sender().confirmTimeouts()).as(sim.describe()).isZero();
            assertThat(sim.stats.forcedTilesTaken).as(sim.describe()).isZero();
            assertThat(sim.stats.resentTileVersions).as(sim.describe()).isZero();
        }
    }

    @Test
    void simFivePercentLossWithReorder() {
        for (SimulatedLink sim : runAll(c -> {
            c.rttNanos = 50 * MS;
            c.loss = 0.05;
            c.jitterNanos = 10 * MS;
            c.duplicates = 0.01;
        })) {
            assertThat(sim.stats.randomDrops).as(sim.describe()).isPositive();
            assertThat(sim.sender().lostPackets()).as(sim.describe()).isPositive();
        }
    }

    @Test
    void simTailLossHeals() {
        for (SimulatedLink sim : runAll(c -> {
            c.rttNanos = 50 * MS;
            c.bytesPerTile = 3000;
            c.changesUntil = 2 * SECOND;
            c.finalChangeAt = 5 * SECOND;
            c.loseFinalTail = true;
        })) {
            assertThat(sim.stats.tailDrops).as(sim.describe()).isEqualTo(1);
            // healed by the PTO probe + time-threshold loss, well before the 1 s assembly expiry would matter
            assertThat(sim.stats.convergedAt).as(sim.describe()).isLessThan(sim.stats.lastChangeAt + 2 * SECOND);
        }
    }

    @Test
    void simResizeUnderLoss() {
        for (SimulatedLink sim : runAll(c -> {
            c.rttNanos = 50 * MS;
            c.loss = 0.05;
            c.changesUntil = 4 * SECOND;
            c.resizeAt = 2 * SECOND;
        })) {
            assertThat(sim.sender().epoch()).isEqualTo(1);
        }
    }

    @Test
    void simTwoMbitConverges() {
        for (SimulatedLink sim : runAll(c -> {
            c.rttNanos = 50 * MS;
            c.bandwidthBps = 2_000_000;
            c.width = 1920;
            c.height = 1080;
            c.bytesPerTile = 1176; // 510 tiles: a 600 KB first frame
            c.changesPerTick = 10; // ~2.8 Mbit/s of changes: more than the link
            c.changesUntil = 10 * SECOND;
        })) {
            double secondHalf = sim.stats.sentBps(5 * SECOND, 10 * SECOND);
            assertThat(secondHalf).as(sim.describe() + ", sent %.0f bit/s".formatted(secondHalf))
                    .isLessThanOrEqualTo(2_500_000);
        }
    }

    @Test
    void simSlowApplyNoResendLoop() {
        // One FIFO decoder: every rect takes 300 ms to apply, one after another, the 48-rect first frame included
        // (about 14 s). The viewer side of the capture gate keeps the backlog short, and the confirm timeout counts
        // from the viewer's last progress, so nothing is resent.
        for (SimulatedLink sim : runAll(c -> {
            c.applyNanos = 300 * MS;
            c.applySerial = true;
            c.changesUntil = 2 * SECOND;
            c.runFor = 60 * SECOND;
        })) {
            assertThat(sim.sender().lostPackets()).as(sim.describe()).isZero();
            assertThat(sim.sender().droppedReports()).as(sim.describe()).isZero();
            assertThat(sim.sender().confirmTimeouts()).as("resends without cause: " + sim.describe()).isZero();
            assertThat(sim.stats.forcedTilesTaken).as(sim.describe()).isZero();
            assertThat(sim.stats.resentTileVersions).as(sim.describe()).isZero();
            assertThat(sim.stats.maxApplyBacklogRects).as(sim.describe()).isPositive(); // the viewer did lag
        }
    }

    @Test
    void simAckLoss() {
        for (SimulatedLink sim : runAll(c -> c.ackLoss = 0.20)) {
            assertThat(sim.stats.ackDrops).as(sim.describe()).isPositive();
        }
    }

    @Test
    void simLostDroppedReportHeals() {
        for (SimulatedLink sim : runAll(c -> {
            c.dropRectOfCapture = 10; // about 0.3 s in, changes go on until 3 s
            c.loseDroppedReport = true;
        })) {
            assertThat(sim.stats.droppedReportsLost).as(sim.describe()).isGreaterThanOrEqualTo(3);
            assertThat(sim.sender().droppedReports()).as(sim.describe()).isZero();
            assertThat(sim.sender().confirmTimeouts()).as(sim.describe()).isPositive(); // the backstop healed it
        }
    }

    @Test
    void simLargeRectAtMinRate() {
        for (SimulatedLink sim : runAll(c -> {
            c.rttNanos = 50 * MS;
            c.bandwidthBps = SendPacer.MIN_BPS;
            c.width = 64;
            c.height = 64; // a single tile
            c.bytesPerTile = 100_000;
            c.changesPerTick = 0;
            c.changesUntil = 0;
        })) {
            // at 0.5 Mbit/s the rect needs ~1.6 s: it assembles only because expiry counts from the last fragment
            assertThat(sim.stats.maxAssemblyNanos).as(sim.describe()).isGreaterThan(SECOND);
            assertThat(sim.stats.forcedTilesTaken).as(sim.describe()).isLessThanOrEqualTo(10);
        }
    }

    @Test
    void simReportsSettledRate() {
        List<SimulatedLink> runs = runAll(c -> {
            c.rttNanos = 50 * MS;
            c.loss = 0.05;
            c.bandwidthBps = 20_000_000;
            c.width = 1920;
            c.height = 1080;
            c.bytesPerTile = 1176;
            c.changesPerTick = 60; // ~17 Mbit/s of changes
            c.changesUntil = 15 * SECOND;
        });
        double[] rates = runs.stream().mapToDouble(s -> s.stats.meanRate(5 * SECOND, 15 * SECOND)).toArray();
        double[] sent = runs.stream().mapToDouble(s -> s.stats.sentBps(5 * SECOND, 15 * SECOND)).toArray();
        System.out.printf("simReportsSettledRate (5%% loss, RTT 50 ms, 20 Mbit/s link, 5-15 s, %d seeds): "
                        + "pacer rate mean %.2f Mbit/s (min %.2f, max %.2f), emitted mean %.2f Mbit/s%n",
                SEEDS, mean(rates) / 1e6, min(rates) / 1e6, max(rates) / 1e6, mean(sent) / 1e6);
        assertThat(rates).hasSize(SEEDS);
    }

    @Test
    void simIdleIsQuiet() {
        // The sim has no NAT keepalive: once converged, an idle agent must send nothing at all.
        List<Consumer<Config>> scenarios = List.of(c -> {}, c -> {
            c.rttNanos = 50 * MS;
            c.loss = 0.05;
            c.jitterNanos = 10 * MS;
        });
        for (Consumer<Config> scenario : scenarios) {
            for (SimulatedLink sim : runAll(scenario)) {
                assertThat(sim.stats.convergedAt).as(sim.describe()).isNotNegative();
                assertThat(sim.config.runFor - sim.stats.convergedAt).as(sim.describe())
                        .isGreaterThanOrEqualTo(3 * SECOND);
                assertThat(sim.stats.agentDatagrams).as(sim.describe()).isPositive();
                assertThat(sim.stats.lastAgentSendAt).as("agent sent after convergence: " + sim.describe())
                        .isLessThanOrEqualTo(sim.stats.convergedAt);
            }
        }
    }

    /**
     * Print only: throughput under a coarse agent timer (Windows' default 15.6 ms tick) against an exact 1 ms one, with
     * a backlog that always exceeds the link (about 2 MB a capture, i.e. far beyond the 200 Mbit/s pacer maximum).
     * Variant "timers": only timer deadlines are rounded up to the tick (pumps driven by ACKs and captures are
     * immediate); "all pumps": every pump waits for the next tick.
     */
    @Test
    void simCoarseTimerThroughput() {
        long[] granularities = {15_600_000L, MS};
        long[] rtts = {MS, 50 * MS};
        long[] links = {0, 50_000_000L};
        StringBuilder table = new StringBuilder(String.format(
                "simCoarseTimerThroughput (1-4 s of a constant backlog, seed 1):%n"
                        + "| variant | timer | RTT | link | achieved Mbit/s | pacer Mbit/s | max queue latency ms |%n"
                        + "|---|---|---|---|---|---|---|%n"));
        int rows = 0;
        for (boolean everyPump : new boolean[] {false, true}) {
            for (long g : granularities) {
                for (long rtt : rtts) {
                    for (long link : links) {
                        SimulatedLink sim = new SimulatedLink(new Config().with(c -> {
                            c.timerGranularityNanos = g;
                            c.roundEveryPump = everyPump;
                            c.rttNanos = rtt;
                            c.bandwidthBps = link;
                            c.width = 1920;
                            c.height = 1080;
                            c.maxMergeTiles = 8;
                            c.bytesPerTile = 4000;
                            c.changesPerTick = 2000; // nearly every tile, every tick
                            c.changesUntil = 4 * SECOND;
                            c.runFor = 4 * SECOND;
                        })).run();
                        table.append(String.format(Locale.ROOT, "| %s | %.1f ms | %d ms | %s | %.1f | %.1f | %.0f |%n",
                                everyPump ? "all pumps" : "timers", g / 1e6, rtt / MS,
                                link == 0 ? "unlimited" : link / 1_000_000 + " Mbit/s",
                                sim.stats.sentBps(SECOND, 4 * SECOND) / 1e6,
                                sim.stats.meanRate(SECOND, 4 * SECOND) / 1e6,
                                sim.stats.maxQueueLatencyNanos / 1e6));
                        rows++;
                    }
                }
            }
        }
        System.out.print(table);
        assertThat(rows).isEqualTo(16);
    }

    private static double mean(double[] v) {
        double s = 0;
        for (double x : v) {
            s += x;
        }
        return s / v.length;
    }

    private static double min(double[] v) {
        double m = Double.MAX_VALUE;
        for (double x : v) {
            m = Math.min(m, x);
        }
        return m;
    }

    private static double max(double[] v) {
        double m = -Double.MAX_VALUE;
        for (double x : v) {
            m = Math.max(m, x);
        }
        return m;
    }
}

package pl.mtech.mremora.agent;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import pl.mtech.mremora.protocol.StreamConfig;
import pl.mtech.mremora.protocol.TileUpdate;
import pl.mtech.mremora.protocol.udp.TileDelivery;
import pl.mtech.mremora.protocol.udp.TileFragment;
import pl.mtech.mremora.protocol.udp.UdpImageSender;
import pl.mtech.mremora.protocol.udp.UdpPacketCodec;

/**
 * Measures encoder output over synthetic scenarios.
 * Intentionally not matched by surefire's default includes (name does not end in Test): runs only via
 * {@code -Dtest='StreamBenchmark*' -Dmremora.bench=true}.
 */
final class StreamBenchmark {

    private static final int WARMUP_FRAMES = 3;
    private static final long SEED = 42;

    /** Times only {@code encodeNext}; frame generation and 3 warm-up frames are excluded. */
    static BenchResult run(Scenario scenario, int width, int height, int frames, StreamEncoder encoder) {
        var screen = new SyntheticScreen(scenario, width, height, SEED);
        for (int i = 0; i < WARMUP_FRAMES; i++) {
            encoder.encodeNext(screen.next());
        }
        long totalBytes = 0;
        long totalNanos = 0;
        for (int i = 0; i < frames; i++) {
            BufferedImage frame = screen.next();
            long start = System.nanoTime();
            int bytes = encoder.encodeNext(frame);
            totalNanos += System.nanoTime() - start;
            totalBytes += bytes;
        }
        return new BenchResult(scenario, frames, totalBytes, totalNanos / 1_000_000.0 / frames);
    }

    static String formatTable(List<BenchResult> results) {
        var sb = new StringBuilder("scenario     frames  avg KB/frame  Mbit/s@30fps  avg encode ms\n");
        for (BenchResult r : results) {
            sb.append(String.format(Locale.ROOT, "%-12s %-7d %-13.1f %-13.1f %.1f%n",
                    r.scenario(), r.frames(), r.totalBytes() / 1024.0 / r.frames(),
                    r.megabitsPerSecondAt(30), r.avgEncodeMillis()));
        }
        return sb.toString();
    }

    @Test
    @EnabledIfSystemProperty(named = "mremora.bench", matches = "true")
    void baselineStage1() throws IOException {
        var jpeg = new JpegFrameEncoder(JpegFrameEncoder.DEFAULT_QUALITY);
        var results = new ArrayList<BenchResult>();
        for (Scenario s : Scenario.values()) {
            results.add(run(s, 2560, 1440, 60, frame -> jpeg.encode(frame).length));
        }
        String table = formatTable(results);
        System.out.println("Stage 1 baseline (full-frame JPEG q=0.7, 2560x1440):");
        System.out.print(table);
        Path out = Path.of("target", "mremora-bench.txt");
        Files.createDirectories(out.getParent());
        Files.writeString(out, table, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    /** Wire bytes of one v2 frame: per part 17 header + 6 frame bytes, per rectangle 13 + data. */
    static int wireBytes(List<TileUpdate> parts) {
        int bytes = 0;
        for (TileUpdate part : parts) {
            bytes += 23;
            for (TileUpdate.Rect rect : part.rects()) {
                bytes += 13 + rect.dataLength();
            }
        }
        return bytes;
    }

    private static StreamEncoder tileEncoder(StreamConfig config, int width, int height) {
        var screen = new MutableScreen(new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB));
        var encoder = new TileFrameEncoder(screen);
        encoder.configure(config);
        return frame -> {
            screen.set(frame);
            return wireBytes(encoder.next());
        };
    }

    @Test
    @EnabledIfSystemProperty(named = "mremora.bench", matches = "true")
    void stage2Tiles() throws IOException {
        var results = new ArrayList<BenchResult>();
        for (Scenario s : Scenario.values()) {
            results.add(run(s, 2560, 1440, 60, tileEncoder(new StreamConfig(1600, 900), 2560, 1440)));
        }
        String table = "v2 tiles @1600x900\n" + formatTable(results);
        System.out.println("v2 tiles @1600x900 (2560x1440 source):");
        System.out.print(table);

        var fullRes = new ArrayList<BenchResult>();
        for (Scenario s : Scenario.values()) {
            fullRes.add(run(s, 2560, 1440, 60, tileEncoder(StreamConfig.FULL_RESOLUTION, 2560, 1440)));
        }
        String fullTable = "v2 tiles @FULL_RESOLUTION (2560x1440)\n" + formatTable(fullRes);
        System.out.println("v2 tiles @FULL_RESOLUTION:");
        System.out.print(fullTable);

        var firstLines = new StringBuilder("first (full) frame bytes, v2:\n");
        for (Scenario s : Scenario.values()) {
            int full = firstFrameBytes(s, StreamConfig.FULL_RESOLUTION);
            int scaled = firstFrameBytes(s, new StreamConfig(1600, 900));
            firstLines.append(String.format(Locale.ROOT, "%-12s FULL_RESOLUTION %d B (%.0f KB)  1600x900 %d B (%.0f KB)%n",
                    s, full, full / 1024.0, scaled, scaled / 1024.0));
        }
        System.out.print(firstLines);

        Path out = Path.of("target", "mremora-bench.txt");
        Files.createDirectories(out.getParent());
        Files.writeString(out, table + fullTable + firstLines, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private static int firstFrameBytes(Scenario scenario, StreamConfig config) {
        var screen = new SyntheticScreen(scenario, 2560, 1440, SEED);
        return tileEncoder(config, 2560, 1440).encodeNext(screen.next());
    }

    private static final List<Scenario> UDP_SCENARIOS = List.of(Scenario.TYPING, Scenario.WINDOW_DRAG, Scenario.VIDEO);
    private static final int[] UDP_MERGE_WIDTHS = {2, 4, 8};
    /** Wire overhead of one fragment: fragment header plus packet header and MAC. */
    private static final int FRAGMENT_OVERHEAD =
            TileFragment.HEADER + UdpPacketCodec.MAX_DATAGRAM - UdpPacketCodec.MAX_PAYLOAD;

    /** Fragments of one encoded rectangle, as {@code RectFragmenter} cuts it. */
    static int fragments(int dataLength) {
        return Math.max(1, (dataLength + TileFragment.MAX_DATA - 1) / TileFragment.MAX_DATA);
    }

    /** Encoded sizes of the rectangles of a run of UDP frames. */
    record RectSizes(int[] bytes, int frames) {

        int percentile(int[] sorted, double q) {
            return sorted.length == 0 ? 0 : sorted[(int) Math.min(sorted.length - 1, Math.floor(q * sorted.length))];
        }

        String row(String label) {
            int[] b = bytes.clone();
            Arrays.sort(b);
            int[] f = Arrays.stream(b).map(StreamBenchmark::fragments).toArray();
            long data = Arrays.stream(b).asLongStream().sum();
            long wire = data + (long) Arrays.stream(f).sum() * FRAGMENT_OVERHEAD;
            return String.format(Locale.ROOT, "%-22s %-7d %-8d %-8d %-8d %-7d %-7d %-6d %-11.1f %.1f%n",
                    label, b.length, percentile(b, 0.5), percentile(b, 0.9), b.length == 0 ? 0 : b[b.length - 1],
                    percentile(f, 0.5), percentile(f, 0.9), f.length == 0 ? 0 : f[f.length - 1],
                    wire / 1024.0 / frames, data / 1024.0 / frames);
        }
    }

    /** The first (full) frame and the following {@code frames} frames of {@code scenario} over UDP. */
    static RectSizes[] udpRectSizes(Scenario scenario, int maxMergeTiles, int frames) {
        var source = new SyntheticScreen(scenario, 2560, 1440, SEED);
        var screen = new MutableScreen(new BufferedImage(2560, 1440, BufferedImage.TYPE_INT_RGB));
        var encoder = new TileFrameEncoder(screen);
        encoder.configure(new StreamConfig(1600, 900));
        var none = new TileDelivery.Forced(0, new BitSet());
        screen.set(source.next());
        int[] first = sizes(encoder.nextForUdp(none, maxMergeTiles).rects());
        var steady = new ArrayList<Integer>();
        for (int i = 0; i < frames; i++) {
            screen.set(source.next());
            for (int b : sizes(encoder.nextForUdp(none, maxMergeTiles).rects())) {
                steady.add(b);
            }
        }
        return new RectSizes[] {
            new RectSizes(first, 1), new RectSizes(steady.stream().mapToInt(Integer::intValue).toArray(), frames)};
    }

    private static int[] sizes(List<UdpImageSender.OutgoingRect> rects) {
        return rects.stream().mapToInt(r -> r.data().length).toArray();
    }

    /** Chooses {@link RectPlanner#UDP_MAX_MERGE_TILES}: rectangle sizes and fragments per merge width. */
    @Test
    @EnabledIfSystemProperty(named = "mremora.bench", matches = "true")
    void udpRectSizes() throws IOException {
        var table = new StringBuilder("UDP rects @1600x900 (2560x1440 source); bytes = encoded rect, frag = fragments"
                + " of " + TileFragment.MAX_DATA + " B; KB/frame incl. " + FRAGMENT_OVERHEAD + " B/fragment overhead\n");
        table.append("scenario/merge/frames  rects   med B    p90 B    max B    medFr   p90Fr   maxFr  wireKB/fr   dataKB/fr\n");
        for (Scenario s : UDP_SCENARIOS) {
            for (int merge : UDP_MERGE_WIDTHS) {
                RectSizes[] r = udpRectSizes(s, merge, 60);
                table.append(r[0].row(s + "/" + merge + "/first"));
                table.append(r[1].row(s + "/" + merge + "/next60"));
            }
        }
        System.out.print(table);
        Path out = Path.of("target", "mremora-bench.txt");
        Files.createDirectories(out.getParent());
        Files.writeString(out, table, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }
}

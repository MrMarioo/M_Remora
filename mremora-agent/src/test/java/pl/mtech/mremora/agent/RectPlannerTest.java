package pl.mtech.mremora.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.BitSet;
import java.util.Random;
import org.junit.jupiter.api.Test;
import pl.mtech.mremora.protocol.TileUpdate.Encoding;

class RectPlannerTest {

    private static BitSet all(int width, int height) {
        var b = new BitSet();
        b.set(0, DirtyTiles.cols(width) * DirtyTiles.rows(height));
        return b;
    }

    private static int[] noise(int w, int h, long seed) {
        var r = new Random(seed);
        int[] p = new int[w * h];
        for (int i = 0; i < p.length; i++) p[i] = r.nextInt(0x1000000);
        return p;
    }

    @Test
    void uniformTileIsSolid() {
        int[] p = new int[64 * 64];
        java.util.Arrays.fill(p, 0x123456);
        assertThat(RectPlanner.plan(p, 64, 64, all(64, 64)))
                .containsExactly(new RectPlanner.PlannedRect(0, 0, 64, 64, Encoding.SOLID, 0x123456));
    }

    @Test
    void fewColorsAreLossless() {
        int[] p = new int[64 * 64];
        for (int i = 0; i < p.length; i++) p[i] = (i / 64) % 2 == 0 ? 0xFF0000 : 0x0000FF;
        var plan = RectPlanner.plan(p, 64, 64, all(64, 64));
        assertThat(plan).hasSize(1);
        assertThat(plan.get(0).encoding()).isEqualTo(Encoding.LOSSLESS);
    }

    @Test
    void exactly64ColorsAreLosslessAnd65AreJpeg() {
        int[] p = new int[64 * 64];
        for (int i = 0; i < p.length; i++) p[i] = i % 64;
        assertThat(RectPlanner.plan(p, 64, 64, all(64, 64)).get(0).encoding()).isEqualTo(Encoding.LOSSLESS);
        p[0] = 1000;
        assertThat(RectPlanner.plan(p, 64, 64, all(64, 64)).get(0).encoding()).isEqualTo(Encoding.JPEG);
    }

    @Test
    void noiseIsJpeg() {
        var plan = RectPlanner.plan(noise(64, 64, 1), 64, 64, all(64, 64));
        assertThat(plan.get(0).encoding()).isEqualTo(Encoding.JPEG);
    }

    @Test
    void adjacentSameClassMerge() {
        int w = 192;
        int[] p = new int[w * 64];
        for (int i = 0; i < p.length; i++) p[i] = (i % 2 == 0) ? 0xFF : 0xFF00;
        var plan = RectPlanner.plan(p, w, 64, all(w, 64));
        assertThat(plan).containsExactly(new RectPlanner.PlannedRect(0, 0, 192, 64, Encoding.LOSSLESS, 0));
    }

    @Test
    void mergeStopsAtEightTiles() {
        int w = 640;
        var plan = RectPlanner.plan(noise(w, 64, 2), w, 64, all(w, 64));
        assertThat(plan).extracting(RectPlanner.PlannedRect::width).containsExactly(512, 128);
        assertThat(plan).extracting(RectPlanner.PlannedRect::x).containsExactly(0, 512);
    }

    @Test
    void solidMergesOnlyTheSameColor() {
        int w = 128;
        int[] p = new int[w * 64];
        for (int y = 0; y < 64; y++) for (int x = 0; x < w; x++) p[y * w + x] = x < 64 ? 1 : 2;
        var plan = RectPlanner.plan(p, w, 64, all(w, 64));
        assertThat(plan).containsExactly(
                new RectPlanner.PlannedRect(0, 0, 64, 64, Encoding.SOLID, 1),
                new RectPlanner.PlannedRect(64, 0, 64, 64, Encoding.SOLID, 2));
    }

    @Test
    void gapsBreakMerging() {
        int w = 192;
        int[] p = new int[w * 64];
        var dirty = new BitSet();
        dirty.set(0);
        dirty.set(2);
        assertThat(RectPlanner.plan(p, w, 64, dirty)).hasSize(2);
    }

    @Test
    void edgeRectsTouchButDoNotPassTheEdge() {
        int w = 1600, h = 900;
        var plan = RectPlanner.plan(noise(w, h, 3), w, h, all(w, h));
        long area = 0;
        for (var r : plan) {
            assertThat(r.x() + r.width()).isLessThanOrEqualTo(w);
            assertThat(r.y() + r.height()).isLessThanOrEqualTo(h);
            area += (long) r.width() * r.height();
        }
        assertThat(area).isEqualTo((long) w * h);
    }

    @Test
    void cleanTilesProduceNothing() {
        assertThat(RectPlanner.plan(new int[64 * 64], 64, 64, new BitSet())).isEmpty();
    }
}

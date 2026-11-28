package pl.mtech.mremora.protocol.udp;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.BitSet;
import org.junit.jupiter.api.Test;

class TileVersionsTest {

    private static BitSet tiles(int... indices) {
        BitSet b = new BitSet();
        for (int i : indices) {
            b.set(i);
        }
        return b;
    }

    @Test
    void firstAlwaysNewer() {
        TileVersions v = new TileVersions(new TileGrid(256, 128));
        assertThat(v.claimNewer(0, 0, 128, 64, 0)).isEqualTo(tiles(0, 1));
    }

    @Test
    void olderSkipped() {
        TileVersions v = new TileVersions(new TileGrid(256, 128));
        assertThat(v.claimNewer(64, 64, 64, 64, 10)).isEqualTo(tiles(5));
        assertThat(v.claimNewer(64, 64, 64, 64, 9)).isEqualTo(tiles());
        assertThat(v.claimNewer(64, 64, 64, 64, 11)).isEqualTo(tiles(5));
    }

    @Test
    void equalSeqSkipped() {
        TileVersions v = new TileVersions(new TileGrid(256, 128));
        assertThat(v.claimNewer(0, 0, 64, 64, 7)).isEqualTo(tiles(0));
        assertThat(v.claimNewer(0, 0, 64, 64, 7)).isEqualTo(tiles());
    }

    @Test
    void partialOverlap() {
        TileVersions v = new TileVersions(new TileGrid(256, 128));
        assertThat(v.claimNewer(64, 0, 64, 64, 12)).isEqualTo(tiles(1));
        assertThat(v.claimNewer(0, 0, 128, 64, 11)).isEqualTo(tiles(0));
    }

    @Test
    void edgeTilesOfOddSize() {
        TileGrid g = new TileGrid(100, 70);
        assertThat(g.cols()).isEqualTo(2);
        assertThat(g.rows()).isEqualTo(2);
        assertThat(g.count()).isEqualTo(4);
        TileVersions v = new TileVersions(g);
        assertThat(v.claimNewer(64, 64, 36, 6, 1)).isEqualTo(tiles(3));
        assertThat(v.claimNewer(0, 0, 100, 70, 2)).isEqualTo(tiles(0, 1, 2, 3));
        assertThat(v.claimNewer(99, 69, 1, 1, 2)).isEqualTo(tiles());
    }

    @Test
    void tilesOfSpansAndClips() {
        TileGrid g = new TileGrid(256, 128);
        assertThat(g.tilesOf(63, 63, 2, 2)).isEqualTo(tiles(0, 1, 4, 5));
        assertThat(g.tilesOf(200, 100, 500, 500)).isEqualTo(tiles(7));
        assertThat(g.tilesOf(0, 0, 0, 10)).isEqualTo(tiles());
        assertThat(g.tilesOf(300, 0, 10, 10)).isEqualTo(tiles());
    }
}

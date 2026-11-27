package pl.mtech.mremora.agent;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class DirtyTilesTest {

    @Test
    void noReferenceMarksEverything() {
        var dirty = DirtyTiles.find(new int[130 * 70], null, 130, 70);
        assertThat(DirtyTiles.cols(130)).isEqualTo(3);
        assertThat(DirtyTiles.rows(70)).isEqualTo(2);
        assertThat(dirty.cardinality()).isEqualTo(6);
    }

    @Test
    void identicalImagesAreClean() {
        int[] a = new int[130 * 70];
        assertThat(DirtyTiles.find(a, a.clone(), 130, 70).cardinality()).isZero();
    }

    @Test
    void onePixelChangeMarksOneTile() {
        int[] a = new int[130 * 70];
        int[] b = a.clone();
        b[65 * 130 + 100] = 1;
        var dirty = DirtyTiles.find(b, a, 130, 70);
        assertThat(dirty.cardinality()).isEqualTo(1);
        assertThat(dirty.get(1 * 3 + 1)).isTrue();
    }

    @Test
    void edgeTileOfNonMultipleSize() {
        int[] a = new int[1600 * 900];
        int[] b = a.clone();
        b[899 * 1600 + 1599] = 7;
        var dirty = DirtyTiles.find(b, a, 1600, 900);
        assertThat(DirtyTiles.rows(900)).isEqualTo(15);
        assertThat(dirty.cardinality()).isEqualTo(1);
        assertThat(dirty.get(15 * DirtyTiles.cols(1600) - 1)).isTrue();
    }
}

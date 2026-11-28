package pl.mtech.mremora.protocol.udp;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class RectFragmenterTest {

    private static CompleteRect rect(int len) {
        byte[] data = new byte[len];
        for (int i = 0; i < len; i++) {
            data[i] = (byte) i;
        }
        return new CompleteRect(2, 800, 600, 77, 9, 64, 128, 256, 64, TileFragment.LOSSLESS, data);
    }

    @Test
    void smallRectOneFragment() {
        List<TileFragment> f = RectFragmenter.split(rect(100));
        assertThat(f).hasSize(1);
        assertThat(f.get(0).offset()).isZero();
        assertThat(f.get(0).data()).hasSize(100);
    }

    @Test
    void exactMultipleOfMaxData() {
        List<TileFragment> f = RectFragmenter.split(rect(2 * TileFragment.MAX_DATA));
        assertThat(f).hasSize(2);
        assertThat(f.get(1).offset()).isEqualTo(TileFragment.MAX_DATA);
        assertThat(f.get(1).data()).hasSize(TileFragment.MAX_DATA);
    }

    @Test
    void largeRectManyFragments() {
        List<TileFragment> f = RectFragmenter.split(rect(5000));
        assertThat(f).hasSize(5);
        assertThat(f.get(4).data()).hasSize(464);
        int expectedOffset = 0;
        for (TileFragment t : f) {
            assertThat(t.offset()).isEqualTo(expectedOffset);
            assertThat(t.totalLength()).isEqualTo(5000);
            expectedOffset += t.data().length;
        }
    }

    @Test
    void fragmentsCarryHeader() {
        for (TileFragment t : RectFragmenter.split(rect(3000))) {
            assertThat(t.epoch()).isEqualTo(2);
            assertThat(t.streamWidth()).isEqualTo(800);
            assertThat(t.streamHeight()).isEqualTo(600);
            assertThat(t.captureSeq()).isEqualTo(77);
            assertThat(t.rectId()).isEqualTo(9);
            assertThat(t.x()).isEqualTo(64);
            assertThat(t.y()).isEqualTo(128);
            assertThat(t.width()).isEqualTo(256);
            assertThat(t.height()).isEqualTo(64);
            assertThat(t.encoding()).isEqualTo(TileFragment.LOSSLESS);
        }
    }

    @Test
    void emptyRectRejected() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> RectFragmenter.split(rect(0)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

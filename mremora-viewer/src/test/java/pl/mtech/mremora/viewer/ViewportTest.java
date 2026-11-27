package pl.mtech.mremora.viewer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ViewportTest {

    @Test
    void fitsExactly() {
        Viewport v = Viewport.fit(1920, 1080, 960, 540);
        assertThat(v.x()).isZero();
        assertThat(v.y()).isZero();
        assertThat(v.width()).isEqualTo(960);
        assertThat(v.height()).isEqualTo(540);
    }

    @Test
    void pillarboxInWideView() {
        Viewport v = Viewport.fit(1920, 1080, 1000, 540);
        assertThat(v.x()).isEqualTo(20);
        assertThat(v.width()).isEqualTo(960);
    }

    @Test
    void letterboxInTallView() {
        Viewport v = Viewport.fit(1920, 1080, 960, 1000);
        assertThat(v.y()).isEqualTo(230);
        assertThat(v.height()).isEqualTo(540);
    }

    @Test
    void upscales() {
        Viewport v = Viewport.fit(32, 16, 64, 32);
        assertThat(v.width()).isEqualTo(64);
        assertThat(v.height()).isEqualTo(32);
    }

    @Test
    void mapsCenterAndLastPixel() {
        Viewport v = Viewport.fit(1920, 1080, 960, 540);
        assertThat(v.toImage(480, 270)).contains(new ImagePoint(960, 540));
        assertThat(v.toImage(959.99, 539.99)).contains(new ImagePoint(1919, 1079));
    }

    @Test
    void outsideImageIsEmpty() {
        Viewport v = Viewport.fit(1920, 1080, 1000, 540);
        assertThat(v.toImage(10, 100)).isEmpty();
        assertThat(v.toImage(500, -1)).isEmpty();
    }

    @Test
    void clampedSticksToEdges() {
        Viewport v = Viewport.fit(1920, 1080, 960, 540);
        assertThat(v.toImageClamped(-5, 2000)).isEqualTo(new ImagePoint(0, 1079));
    }

    @Test
    void zeroSizedViewIsEmpty() {
        Viewport v = Viewport.fit(1920, 1080, 0, 540);
        assertThat(v.isEmpty()).isTrue();
        assertThat(v.toImage(0, 0)).isEmpty();
        assertThatThrownBy(() -> v.toImageClamped(0, 0)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void mapsThroughPillarboxOffset() {
        Viewport v = Viewport.fit(1920, 1080, 1000, 540);
        assertThat(v.toImage(500, 270)).contains(new ImagePoint(960, 540));
        assertThat(v.toImageClamped(10, 100)).isEqualTo(new ImagePoint(0, 200));
    }

    @Test
    void edgesAreHalfOpen() {
        Viewport v = Viewport.fit(1920, 1080, 1000, 540);
        assertThat(v.toImage(20, 0)).contains(new ImagePoint(0, 0));
        assertThat(v.toImage(980, 0)).isEmpty();
        assertThat(v.toImage(500, 540)).isEmpty();
    }

    @Test
    void mappingUsesScreenPixelsNotStreamPixels() {
        Viewport v = Viewport.fit(2560, 1440, 1600, 900);
        assertThat(v.toImage(1599.9, 899.9)).contains(new ImagePoint(2559, 1439));
        assertThat(v.toImage(800, 450)).contains(new ImagePoint(1280, 720));
    }
}

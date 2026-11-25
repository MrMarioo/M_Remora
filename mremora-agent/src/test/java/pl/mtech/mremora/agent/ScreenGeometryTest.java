package pl.mtech.mremora.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.awt.Point;
import java.awt.Rectangle;
import org.junit.jupiter.api.Test;
import pl.mtech.mremora.protocol.ScreenInfo;

class ScreenGeometryTest {

    @Test
    void infoAtNoScaling() {
        var geometry = new ScreenGeometry(new Rectangle(0, 0, 1920, 1080), 1.0);

        assertThat(geometry.info()).isEqualTo(new ScreenInfo(1920, 1080, 100));
    }

    @Test
    void infoAt125Percent() {
        var geometry = new ScreenGeometry(new Rectangle(0, 0, 1536, 864), 1.25);

        assertThat(geometry.info()).isEqualTo(new ScreenInfo(1920, 1080, 125));
    }

    @Test
    void toLogicalDividesByScale() {
        var geometry = new ScreenGeometry(new Rectangle(0, 0, 1536, 864), 1.25);

        assertThat(geometry.toLogical(1919, 1079)).isEqualTo(new Point(1535, 863));
        assertThat(geometry.toLogical(0, 0)).isEqualTo(new Point(0, 0));
    }

    @Test
    void toLogicalAddsScreenOrigin() {
        var geometry = new ScreenGeometry(new Rectangle(1920, 0, 1920, 1080), 1.0);

        assertThat(geometry.toLogical(0, 0)).isEqualTo(new Point(1920, 0));
    }

    @Test
    void toLogicalClampsOutOfRange() {
        var geometry = new ScreenGeometry(new Rectangle(0, 0, 1536, 864), 1.25);

        assertThat(geometry.toLogical(-5, 5000)).isEqualTo(new Point(0, 863));
        assertThat(geometry.toLogical(99999, -1)).isEqualTo(new Point(1535, 0));
    }

    @Test
    void rejectsNonPositiveScale() {
        var bounds = new Rectangle(0, 0, 1920, 1080);

        assertThatThrownBy(() -> new ScreenGeometry(bounds, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ScreenGeometry(bounds, -1.0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ScreenGeometry(bounds, Double.NaN)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsEmptyBounds() {
        assertThatThrownBy(() -> new ScreenGeometry(new Rectangle(0, 0, 0, 1080), 1.0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ScreenGeometry(new Rectangle(0, 0, 1920, 0), 1.0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

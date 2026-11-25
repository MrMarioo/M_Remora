package pl.mtech.mremora.viewer;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import pl.mtech.mremora.protocol.Message;
import pl.mtech.mremora.protocol.MouseButton;
import pl.mtech.mremora.protocol.MouseMove;
import pl.mtech.mremora.protocol.MouseWheel;

class InputTranslatorTest {

    private final List<Message> out = new ArrayList<>();
    private InputTranslator translator;

    @BeforeEach
    void setUp() {
        translator = new InputTranslator(out::add);
        translator.setViewport(Viewport.fit(32, 16, 64, 32));
    }

    private void pillarbox() {
        translator.setViewport(Viewport.fit(32, 16, 100, 32));
    }

    @Test
    void moveInsideImageSendsAgentPixels() {
        translator.moved(20, 10);
        assertThat(out).containsExactly(new MouseMove(10, 5));
    }

    @Test
    void repeatedPointIsNotResent() {
        translator.moved(20, 10);
        translator.moved(21, 11);
        assertThat(out).containsExactly(new MouseMove(10, 5));
    }

    @Test
    void moveOutsideImageWithoutButtonIsIgnored() {
        pillarbox();
        translator.moved(5, 10);
        assertThat(out).isEmpty();
    }

    @Test
    void clickSendsMoveThenPress() {
        translator.pressed(MouseButton.Button.LEFT, 20, 10);
        assertThat(out).containsExactly(new MouseMove(10, 5), new MouseButton(MouseButton.Button.LEFT, true));
    }

    @Test
    void pressAtUnchangedPointStillSendsMove() {
        translator.moved(20, 10);
        translator.pressed(MouseButton.Button.LEFT, 20, 10);
        assertThat(out).containsExactly(
                new MouseMove(10, 5), new MouseMove(10, 5), new MouseButton(MouseButton.Button.LEFT, true));
    }

    @Test
    void releaseAtUnchangedPointStillSendsMove() {
        translator.pressed(MouseButton.Button.LEFT, 20, 10);
        translator.released(MouseButton.Button.LEFT, 20, 10);
        assertThat(out).containsExactly(
                new MouseMove(10, 5),
                new MouseButton(MouseButton.Button.LEFT, true),
                new MouseMove(10, 5),
                new MouseButton(MouseButton.Button.LEFT, false));
    }

    @Test
    void pressOutsideImageIsIgnored() {
        pillarbox();
        translator.pressed(MouseButton.Button.LEFT, 5, 10);
        translator.released(MouseButton.Button.LEFT, 5, 10);
        assertThat(out).isEmpty();
    }

    @Test
    void dragOutsideIsClampedAndReleaseArrives() {
        translator.pressed(MouseButton.Button.LEFT, 20, 10);
        translator.moved(500, -50);
        translator.released(MouseButton.Button.LEFT, 500, -50);
        assertThat(out).containsExactly(
                new MouseMove(10, 5),
                new MouseButton(MouseButton.Button.LEFT, true),
                new MouseMove(31, 0),
                new MouseMove(31, 0),
                new MouseButton(MouseButton.Button.LEFT, false));
    }

    @Test
    void doublePressIsSentOnce() {
        translator.pressed(MouseButton.Button.LEFT, 20, 10);
        translator.pressed(MouseButton.Button.LEFT, 20, 10);
        assertThat(out).containsExactly(new MouseMove(10, 5), new MouseButton(MouseButton.Button.LEFT, true));
    }

    @Test
    void releaseAllReleasesHeldButtonsOnce() {
        translator.pressed(MouseButton.Button.RIGHT, 20, 10);
        translator.pressed(MouseButton.Button.LEFT, 20, 10);
        translator.releaseAll();
        assertThat(out).endsWith(
                new MouseButton(MouseButton.Button.LEFT, false), new MouseButton(MouseButton.Button.RIGHT, false));
        int size = out.size();
        translator.releaseAll();
        assertThat(out).hasSize(size);
    }

    @Test
    void wheelUpIsNegative() {
        translator.scrolled(40, 40);
        assertThat(out).containsExactly(new MouseWheel(-1));
        out.clear();
        translator.scrolled(-80, 40);
        assertThat(out).containsExactly(new MouseWheel(2));
    }

    @Test
    void touchpadDeltasAccumulate() {
        for (int i = 0; i < 3; i++) {
            translator.scrolled(-10, 40);
            assertThat(out).isEmpty();
        }
        translator.scrolled(-10, 40);
        assertThat(out).containsExactly(new MouseWheel(1));
    }

    @Test
    void zeroMultiplierFallsBackTo40() {
        translator.scrolled(-40, 0);
        assertThat(out).containsExactly(new MouseWheel(1));
    }

    @Test
    void emptyViewportIgnoresEverything() {
        translator.setViewport(Viewport.fit(32, 16, 0, 0));
        translator.moved(20, 10);
        translator.pressed(MouseButton.Button.LEFT, 20, 10);
        assertThat(out).isEmpty();
    }

    @Test
    void releaseWithEmptyViewportStillSendsButtonUp() {
        translator.pressed(MouseButton.Button.LEFT, 20, 10);
        translator.setViewport(Viewport.fit(32, 16, 0, 0));
        translator.released(MouseButton.Button.LEFT, 20, 10);
        assertThat(out).containsExactly(
                new MouseMove(10, 5),
                new MouseButton(MouseButton.Button.LEFT, true),
                new MouseButton(MouseButton.Button.LEFT, false));
    }

    @Test
    void residualCarriesOver() {
        translator.scrolled(-60, 40);
        assertThat(out).containsExactly(new MouseWheel(1));
        translator.scrolled(-20, 40);
        assertThat(out).containsExactly(new MouseWheel(1), new MouseWheel(1));
    }

    @Test
    void nonFiniteWheelIgnored() {
        translator.scrolled(Double.NaN, 40);
        translator.scrolled(Double.POSITIVE_INFINITY, 40);
        translator.scrolled(-40, 40);
        assertThat(out).containsExactly(new MouseWheel(1));
    }
}

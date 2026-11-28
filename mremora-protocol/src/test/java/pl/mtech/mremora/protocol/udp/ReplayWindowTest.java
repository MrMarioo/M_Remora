package pl.mtech.mremora.protocol.udp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ReplayWindowTest {

    @Test
    void exampleFromTheTask() {
        var w = new ReplayWindow(4);
        w.accept(5);
        assertThat(w.isFresh(5)).isFalse();
        assertThat(w.isFresh(3)).isTrue();
        w.accept(3);
        w.accept(9);
        assertThat(w.isFresh(5)).isFalse();
        assertThat(w.isFresh(6)).isTrue();
        assertThat(w.isFresh(100)).isTrue();
    }

    @Test
    void freshBeforeFirstAccept() {
        var w = new ReplayWindow(1024);
        assertThat(w.isFresh(0)).isTrue();
        assertThat(w.isFresh(1L << 62)).isTrue();
    }

    @Test
    void duplicateRejected() {
        var w = new ReplayWindow(1024);
        w.accept(42);
        assertThat(w.isFresh(42)).isFalse();
    }

    @Test
    void oldestInWindowStillFresh() {
        var w = new ReplayWindow(4);
        w.accept(9);
        assertThat(w.isFresh(6)).isTrue();
        assertThat(w.isFresh(5)).isFalse();
    }

    @Test
    void bigJumpClearsWindow() {
        var w = new ReplayWindow(1024);
        w.accept(1);
        w.accept(1_000_000);
        assertThat(w.isFresh(999_999)).isTrue();
        assertThat(w.isFresh(1)).isFalse();
    }

    @Test
    void acceptStaleThrows() {
        var w = new ReplayWindow(4);
        w.accept(9);
        assertThatThrownBy(() -> w.accept(5)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> w.accept(9)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void sizeOneWindow() {
        var w = new ReplayWindow(1);
        w.accept(3);
        assertThat(w.isFresh(3)).isFalse();
        assertThat(w.isFresh(4)).isTrue();
        assertThat(w.isFresh(2)).isFalse();
    }

    @Test
    void outOfOrderInsideWindow() {
        var w = new ReplayWindow(1024);
        w.accept(10);
        w.accept(8);
        w.accept(9);
        assertThat(w.isFresh(8)).isFalse();
        assertThat(w.isFresh(9)).isFalse();
        assertThat(w.isFresh(10)).isFalse();
        assertThat(w.isFresh(7)).isTrue();
    }

    @Test
    void slotReusedAfterWindowMoves() {
        var w = new ReplayWindow(4);
        w.accept(1);
        w.accept(4);
        assertThat(w.isFresh(5)).isTrue(); // slot 1 held number 1, which is now out of the window
        w.accept(5);
        assertThat(w.isFresh(5)).isFalse();
    }

    @Test
    void invalidSizeRejected() {
        assertThatThrownBy(() -> new ReplayWindow(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ReplayWindow(65_537)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void randomAgainstReference() {
        int size = 64;
        var w = new ReplayWindow(size);
        Set<Long> accepted = new HashSet<>();
        long max = -1;
        var random = new Random(42);
        long counter = 0;
        for (int i = 0; i < 100_000; i++) {
            counter += random.nextInt(3);
            long n = Math.max(0, counter + random.nextInt(200) - 150);
            boolean expected = !accepted.contains(n) && (max < 0 || n > max - size);
            assertThat(w.isFresh(n)).as("number %d at step %d", n, i).isEqualTo(expected);
            if (expected && random.nextBoolean()) {
                w.accept(n);
                accepted.add(n);
                max = Math.max(max, n);
            }
        }
    }
}

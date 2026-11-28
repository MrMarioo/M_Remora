package pl.mtech.mremora.protocol.udp;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import pl.mtech.mremora.protocol.udp.RangeSet.Range;

class RangeSetTest {

    private static RangeSet of(long... values) {
        RangeSet s = new RangeSet();
        for (long v : values) {
            s.add(v);
        }
        return s;
    }

    @Test
    void mergesNeighbours() {
        RangeSet s = of(1, 2, 3, 4, 6, 7, 8, 9);
        assertThat(s.newest(10)).containsExactly(new Range(6, 9), new Range(1, 4));

        s.add(5);

        assertThat(s.newest(10)).containsExactly(new Range(1, 9));
    }

    @Test
    void bridgesGap() {
        RangeSet s = of(1, 3);
        s.add(2);
        assertThat(s.newest(10)).containsExactly(new Range(1, 3));
        RangeSet t = of(0, 2);
        t.add(1);
        assertThat(t.newest(10)).containsExactly(new Range(0, 2));
    }

    @Test
    void duplicateNoop() {
        RangeSet s = of(1, 2, 3, 10);
        s.add(2);
        s.add(10);
        assertThat(s.newest(10)).containsExactly(new Range(10, 10), new Range(1, 3));
    }

    @Test
    void newestDescendingCapped() {
        RangeSet s = of(1, 3, 5, 7, 9);
        assertThat(s.newest(3)).containsExactly(new Range(9, 9), new Range(7, 7), new Range(5, 5));
        assertThat(s.newest(0)).isEmpty();
        assertThat(s.newest(99)).hasSize(5);
        assertThat(s.max()).isEqualTo(9);
        assertThat(new RangeSet().isEmpty()).isTrue();
        assertThat(new RangeSet().newest(5)).isEmpty();
    }

    @Test
    void removeBelowTrims() {
        RangeSet s = of(1, 2, 3, 4, 5, 6, 10, 11, 20);
        s.removeBelow(4); // cuts [1-6] in the middle
        assertThat(s.newest(10)).containsExactly(new Range(20, 20), new Range(10, 11), new Range(4, 6));
        s.removeBelow(11);
        assertThat(s.newest(10)).containsExactly(new Range(20, 20), new Range(11, 11));
        assertThat(s.contains(10)).isFalse();
        s.removeBelow(100);
        assertThat(s.isEmpty()).isTrue();
    }

    @Test
    void randomAgainstTreeSet() {
        Random rnd = new Random(1);
        RangeSet s = new RangeSet();
        TreeSet<Long> ref = new TreeSet<>();
        for (int i = 0; i < 10_000; i++) {
            if (rnd.nextInt(20) == 0) {
                long n = rnd.nextInt(600);
                s.removeBelow(n);
                ref.headSet(n).clear();
            } else {
                long n = rnd.nextInt(600);
                s.add(n);
                ref.add(n);
            }
            long probe = rnd.nextInt(600);
            assertThat(s.contains(probe)).isEqualTo(ref.contains(probe));
            assertThat(s.isEmpty()).isEqualTo(ref.isEmpty());
            if (!ref.isEmpty()) {
                assertThat(s.max()).isEqualTo(ref.last());
            }
        }
        List<Long> expanded = new ArrayList<>();
        for (Range r : s.newest(Integer.MAX_VALUE)) {
            for (long n = r.to(); n >= r.from(); n--) {
                expanded.add(n);
            }
        }
        assertThat(expanded).containsExactlyElementsOf(ref.descendingSet());
    }
}

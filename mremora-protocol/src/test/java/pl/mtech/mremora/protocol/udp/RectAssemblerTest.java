package pl.mtech.mremora.protocol.udp;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import org.junit.jupiter.api.Test;

class RectAssemblerTest {

    private static final long MS = 1_000_000L;

    private static CompleteRect rect(int epoch, long rectId, int w, int len, long seed) {
        byte[] data = new byte[len];
        new Random(seed).nextBytes(data);
        return new CompleteRect(epoch, 1000, 800, rectId * 10, rectId, 64, 64, w, 64, TileFragment.LOSSLESS, data);
    }

    private static CompleteRect rect(long rectId, int len) {
        return rect(0, rectId, 64, len, rectId);
    }

    private final RectAssembler asm = new RectAssembler(RectAssembler.EXPIRE_NANOS, RectAssembler.MAX_BYTES);

    @Test
    void inOrderCompletes() {
        CompleteRect r = rect(1, 3000);
        Optional<CompleteRect> out = Optional.empty();
        for (TileFragment f : RectFragmenter.split(r)) {
            assertThat(out).isEmpty();
            out = asm.add(f, 0);
        }
        assertThat(out).isPresent();
        CompleteRect c = out.get();
        assertThat(c.data()).isEqualTo(r.data());
        assertThat(c.rectId()).isEqualTo(1);
        assertThat(c.captureSeq()).isEqualTo(10);
        assertThat(c.width()).isEqualTo(64);
        assertThat(asm.heldBytes()).isZero();
    }

    @Test
    void outOfOrderCompletes() {
        CompleteRect r = rect(1, 3000);
        List<TileFragment> f = new ArrayList<>(RectFragmenter.split(r));
        Collections.reverse(f);
        Optional<CompleteRect> out = Optional.empty();
        for (TileFragment t : f) {
            out = asm.add(t, 0);
        }
        assertThat(out).isPresent();
        assertThat(out.get().data()).isEqualTo(r.data());
    }

    @Test
    void duplicateIgnored() {
        List<TileFragment> f = RectFragmenter.split(rect(1, 2500));
        assertThat(asm.add(f.get(0), 0)).isEmpty();
        assertThat(asm.add(f.get(0), 0)).isEmpty();
        assertThat(asm.add(f.get(1), 0)).isEmpty();
        assertThat(asm.add(f.get(2), 0)).isPresent();
        for (TileFragment t : f) {
            assertThat(asm.add(t, 0)).isEmpty();
        }
        assertThat(asm.takeDropped()).isEmpty();
    }

    @Test
    void overlapIgnored() {
        CompleteRect r = rect(1, 2000);
        TileFragment a = new TileFragment(0, 1000, 800, 10, 1, 64, 64, 64, 64, 2, 2000, 0, slice(r, 0, 1000));
        TileFragment overlap = new TileFragment(0, 1000, 800, 10, 1, 64, 64, 64, 64, 2, 2000, 500, slice(r, 500, 1500));
        TileFragment b = new TileFragment(0, 1000, 800, 10, 1, 64, 64, 64, 64, 2, 2000, 1000, slice(r, 1000, 2000));
        assertThat(asm.add(a, 0)).isEmpty();
        assertThat(asm.add(overlap, 0)).isEmpty();
        Optional<CompleteRect> out = asm.add(b, 0);
        assertThat(out).isPresent();
        assertThat(out.get().data()).isEqualTo(r.data());
    }

    private static byte[] slice(CompleteRect r, int from, int to) {
        return java.util.Arrays.copyOfRange(r.data(), from, to);
    }

    @Test
    void conflictingHeaderDropsRect() {
        List<TileFragment> f = RectFragmenter.split(rect(0, 5, 64, 3000, 1));
        List<TileFragment> other = RectFragmenter.split(rect(0, 5, 128, 3000, 1));
        asm.add(f.get(0), 0);
        assertThat(asm.add(other.get(1), 0)).isEmpty();
        assertThat(asm.takeDropped()).containsExactly(5L);
        assertThat(asm.takeDropped()).isEmpty();
        assertThat(asm.heldBytes()).isZero();
        assertThat(asm.add(f.get(1), 0)).isEmpty();
        assertThat(asm.add(f.get(2), 0)).isEmpty();
        assertThat(asm.add(f.get(0), 0)).isEmpty();
    }

    @Test
    void expiresAfterOneSecond() {
        asm.add(RectFragmenter.split(rect(7, 3000)).get(0), 0);
        assertThat(asm.expire(999 * MS)).isEmpty();
        assertThat(asm.expire(1000 * MS)).containsExactly(7L);
        assertThat(asm.heldBytes()).isZero();
        assertThat(asm.add(RectFragmenter.split(rect(7, 3000)).get(1), 1001 * MS)).isEmpty();
        assertThat(asm.heldBytes()).isZero();
    }

    @Test
    void expiryCountsFromLastFragment() {
        List<TileFragment> f = RectFragmenter.split(rect(7, 3000));
        asm.add(f.get(0), 0);
        asm.add(f.get(1), 900 * MS);
        assertThat(asm.expire(1500 * MS)).isEmpty();
        assertThat(asm.expire(1900 * MS)).containsExactly(7L);
    }

    @Test
    void duplicatesDoNotExtendExpiry() {
        List<TileFragment> f = RectFragmenter.split(rect(7, 3000));
        asm.add(f.get(0), 0);
        asm.add(f.get(0), 900 * MS);
        assertThat(asm.expire(999 * MS)).isEmpty();
        assertThat(asm.expire(1000 * MS)).containsExactly(7L);
    }

    @Test
    void manyTinyPartialsAreBounded() {
        int n = RectAssembler.MAX_PARTIALS + 10;
        for (long id = 1; id <= n; id++) {
            TileFragment t = new TileFragment(0, 1000, 800, 1, id, 0, 0, 8, 8, 2, 2, 0, new byte[1]);
            assertThat(asm.add(t, id)).isEmpty();
            assertThat(asm.heldBytes()).isLessThanOrEqualTo(2 * RectAssembler.MAX_PARTIALS);
        }
        assertThat(asm.heldBytes()).isEqualTo(2 * RectAssembler.MAX_PARTIALS);
        assertThat(asm.takeDropped()).hasSize(10).startsWith(1L, 2L, 3L);
    }

    @Test
    void memoryCapEvictsOldest() {
        RectAssembler small = new RectAssembler(RectAssembler.EXPIRE_NANOS, 4000);
        for (long id = 1; id <= 3; id++) {
            small.add(RectFragmenter.split(rect(id, 2000)).get(0), id);
            assertThat(small.heldBytes()).isLessThanOrEqualTo(4000);
        }
        assertThat(small.takeDropped()).containsExactly(1L);
        assertThat(small.heldBytes()).isEqualTo(4000);
    }

    @Test
    void newerEpochDropsOlderIncomplete() {
        assertThat(asm.currentEpoch()).isEqualTo(-1);
        asm.add(RectFragmenter.split(rect(0, 1, 64, 3000, 1)).get(0), 0);
        assertThat(asm.currentEpoch()).isZero();
        assertThat(asm.add(RectFragmenter.split(rect(1, 2, 64, 100, 2)).get(0), 0)).isPresent();
        assertThat(asm.currentEpoch()).isEqualTo(1);
        assertThat(asm.takeDropped()).containsExactly(1L);
        assertThat(asm.heldBytes()).isZero();
    }

    @Test
    void olderEpochIgnored() {
        asm.add(RectFragmenter.split(rect(1, 1, 64, 100, 1)).get(0), 0);
        assertThat(asm.add(RectFragmenter.split(rect(0, 2, 64, 100, 2)).get(0), 0)).isEmpty();
        assertThat(asm.currentEpoch()).isEqualTo(1);
        assertThat(asm.takeDropped()).containsExactly(2L);
        assertThat(asm.heldBytes()).isZero();
    }

    @Test
    void roundTripWithFragmenter() {
        Random rnd = new Random(3);
        List<CompleteRect> rects = new ArrayList<>();
        List<TileFragment> all = new ArrayList<>();
        for (int i = 1; i <= 50; i++) {
            CompleteRect r = rect(0, i, 64, 1 + rnd.nextInt(20000), i);
            rects.add(r);
            all.addAll(RectFragmenter.split(r));
        }
        Collections.shuffle(all, rnd);
        List<CompleteRect> got = new ArrayList<>();
        for (TileFragment f : all) {
            asm.add(f, 0).ifPresent(got::add);
        }
        assertThat(got).hasSize(50);
        for (CompleteRect c : got) {
            assertThat(c.data()).isEqualTo(rects.get((int) c.rectId() - 1).data());
        }
        assertThat(asm.heldBytes()).isZero();
        assertThat(asm.takeDropped()).isEmpty();
    }
}

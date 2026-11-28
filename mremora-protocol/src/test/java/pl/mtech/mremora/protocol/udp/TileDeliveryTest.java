package pl.mtech.mremora.protocol.udp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.BitSet;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import pl.mtech.mremora.protocol.udp.TileDelivery.Forced;

class TileDeliveryTest {

    private static final long MS = 1_000_000L;

    /** 256×128 stream: 4 columns × 2 rows. */
    private final TileGrid grid = new TileGrid(256, 128);
    private final TileDelivery d = new TileDelivery();

    private static BitSet tiles(int... indices) {
        BitSet b = new BitSet();
        for (int i : indices) {
            b.set(i);
        }
        return b;
    }

    @BeforeEach
    void setUp() {
        d.newEpoch(0, grid);
    }

    @Test
    void designTimeline() {
        int f = 5;
        // t=0: capture #10, tile F changed -> R1 in packets 200..203
        long r1 = d.register(0, 10, tiles(f), 4000);
        for (long n = 200; n <= 203; n++) {
            d.packetSent(r1, n);
        }
        // t=33: nothing forced yet (F in flight)
        assertThat(d.takeForced().tiles()).isEqualTo(tiles());
        // t=50: ACK 200, 201, 203 -> 202 lost
        d.packetsAcked(List.of(200L, 201L, 203L), 50 * MS);
        d.packetLost(202);
        Forced forced = d.takeForced();
        assertThat(forced.epoch()).isZero();
        assertThat(forced.tiles()).isEqualTo(tiles(f));
        assertThat(d.unconfirmedBytes()).isZero();
        // t=66: capture #12 resends current F as R2
        long r2 = d.register(0, 12, tiles(f), 3000);
        assertThat(r2).isNotEqualTo(r1);
        d.packetSent(r2, 211);
        d.packetsAcked(List.of(211L), 116 * MS);
        d.done(r2);
        assertThat(d.unconfirmedBytes()).isZero();
        // late "R1 done": nothing changes
        d.done(r1);
        assertThat(d.takeForced().tiles()).isEqualTo(tiles());
        assertThat(d.unconfirmedBytes()).isZero();
        d.checkConfirmTimeout(10_000 * MS);
        assertThat(d.takeForced().tiles()).isEqualTo(tiles());
    }

    @Test
    void lossOfOlderVersionIgnored() {
        long r1 = d.register(0, 10, tiles(3), 100);
        d.packetSent(r1, 1);
        long r2 = d.register(0, 11, tiles(3), 100);
        d.packetSent(r2, 2);
        d.packetLost(1);
        assertThat(d.takeForced().tiles()).isEqualTo(tiles());
        assertThat(d.unconfirmedBytes()).isEqualTo(100);
    }

    @Test
    void lossForcesOnlyTilesStillAtItsVersion() {
        long r1 = d.register(0, 10, tiles(1, 2), 100);
        d.packetSent(r1, 1);
        long r2 = d.register(0, 11, tiles(1), 100);
        d.packetSent(r2, 2);
        d.packetLost(1);
        assertThat(d.takeForced().tiles()).isEqualTo(tiles(2));
    }

    @Test
    void droppedForcesTiles() {
        long r = d.register(0, 10, tiles(0, 7), 500);
        d.packetSent(r, 1);
        d.packetsAcked(List.of(1L), 10 * MS);
        d.dropped(r);
        assertThat(d.takeForced().tiles()).isEqualTo(tiles(0, 7));
        assertThat(d.unconfirmedBytes()).isZero();
        // terminal: done afterwards changes nothing
        d.done(r);
        assertThat(d.unconfirmedBytes()).isZero();
        assertThat(d.takeForced().tiles()).isEqualTo(tiles());
    }

    @Test
    void confirmTimeoutNotPto() {
        long r = d.register(0, 10, tiles(4), 100);
        d.packetSent(r, 1);
        d.packetSent(r, 2);
        d.packetsAcked(List.of(1L, 2L), 100 * MS);
        d.checkConfirmTimeout(100 * MS + 1_900 * MS);
        assertThat(d.takeForced().tiles()).isEqualTo(tiles());
        assertThat(d.unconfirmedBytes()).isEqualTo(100);
        d.checkConfirmTimeout(100 * MS + TileDelivery.CONFIRM_TIMEOUT_NANOS);
        assertThat(d.takeForced().tiles()).isEqualTo(tiles(4));
        assertThat(d.unconfirmedBytes()).isZero();
    }

    @Test
    void confirmTimeoutCountsFromLastViewerProgress() {
        long slow = d.register(0, 10, tiles(4), 100);
        long other = d.register(0, 11, tiles(5), 100);
        d.packetSent(slow, 1);
        d.packetSent(other, 2);
        d.packetsAcked(List.of(1L, 2L), 0);
        // the viewer keeps finishing other rects: the waiting one is not forced although acked 3 s ago
        assertThat(d.done(other)).isTrue();
        d.viewerProgress(1_500 * MS);
        d.checkConfirmTimeout(3_000 * MS);
        assertThat(d.takeForced().tiles()).isEqualTo(tiles());
        assertThat(d.isLive(slow)).isTrue();
        // no progress for 2 s after the last one: forced
        assertThat(d.checkConfirmTimeout(3_500 * MS)).isEqualTo(1);
        assertThat(d.takeForced().tiles()).isEqualTo(tiles(4));
        assertThat(d.isLive(slow)).isFalse();
    }

    @Test
    void overtakenRectIsCappedDespiteProgress() {
        long stuck = d.register(0, 10, tiles(4), 100); // its dropped report never arrives
        d.packetSent(stuck, 1);
        d.packetsAcked(List.of(1L), 0);
        long seq = 11;
        long number = 2;
        for (long t = 300; t < 11_000; t += 300) { // newer rects keep completing every 300 ms
            long newer = d.register(0, seq++, tiles(5), 100);
            d.packetSent(newer, number);
            d.packetsAcked(List.of(number++), t * MS);
            d.done(newer);
            d.viewerProgress(t * MS);
            d.checkConfirmTimeout(t * MS); // first seen overtaken at 300 ms
            if (t < 10_300) {
                assertThat(d.takeForced().tiles()).as("t=%d", t).isEqualTo(tiles());
            }
        }
        assertThat(d.isLive(stuck)).isFalse(); // capped at 300 ms + 10 s
        assertThat(d.takeForced().tiles()).isEqualTo(tiles(4));
    }

    @Test
    void newerDroppedRectDoesNotOvertakeTheApplyBacklog() {
        long queued = d.register(0, 10, tiles(4), 100); // assembled, waiting in a slow apply backlog
        long expired = d.register(0, 11, tiles(5), 100); // newer, dropped at receive time (assembly expiry)
        d.packetSent(queued, 1);
        d.packetSent(expired, 2);
        d.packetsAcked(List.of(1L, 2L), 0);
        assertThat(d.dropped(expired)).isTrue();
        d.viewerProgress(0);
        long seq = 12;
        long number = 3;
        for (long t = 300; t <= 12_000; t += 300) { // the viewer keeps making progress on other, older-applied work
            d.viewerProgress(t * MS);
            assertThat(d.checkConfirmTimeout(t * MS)).as("t=%d", t).isZero();
        }
        assertThat(d.isLive(queued)).isTrue();
        assertThat(d.takeForced().tiles()).isEqualTo(tiles(5)); // only the dropped rect's tile
        // once a newer rect is reported done, the cap applies again
        long newer = d.register(0, seq, tiles(6), 100);
        d.packetSent(newer, number);
        d.packetsAcked(List.of(number), 12_000 * MS);
        assertThat(d.done(newer)).isTrue();
        d.viewerProgress(12_000 * MS);
        d.checkConfirmTimeout(12_100 * MS); // first seen overtaken
        assertThat(d.isLive(queued)).isTrue();
        for (long t = 12_400; t < 22_100; t += 300) {
            d.viewerProgress(t * MS);
            d.checkConfirmTimeout(t * MS);
        }
        assertThat(d.isLive(queued)).isTrue();
        d.viewerProgress(22_100 * MS);
        assertThat(d.checkConfirmTimeout(22_100 * MS)).isEqualTo(1);
        assertThat(d.takeForced().tiles()).isEqualTo(tiles(4));
    }

    @Test
    void fifoBacklogIsNeverCapped() {
        int n = 70; // 21 s of work at 300 ms per rect, all acknowledged at once
        long[] ids = new long[n];
        for (int i = 0; i < n; i++) {
            ids[i] = d.register(0, 10 + i, tiles(i % 8), 100);
            d.packetSent(ids[i], i + 1);
        }
        List<Long> all = new java.util.ArrayList<>();
        for (long i = 1; i <= n; i++) {
            all.add(i);
        }
        d.packetsAcked(all, 0);
        for (int i = 0; i < n; i++) { // done strictly in order
            long t = 300L * (i + 1);
            d.done(ids[i]);
            d.viewerProgress(t * MS);
            assertThat(d.checkConfirmTimeout(t * MS)).as("t=%d", t).isZero();
        }
        assertThat(d.takeForced().tiles()).isEqualTo(tiles());
    }

    @Test
    void progressBeforeTheAckDoesNotShortenTheTimeout() {
        d.viewerProgress(0);
        long r = d.register(0, 10, tiles(4), 100);
        d.packetSent(r, 1);
        d.packetsAcked(List.of(1L), 1_000 * MS);
        d.checkConfirmTimeout(2_999 * MS);
        assertThat(d.takeForced().tiles()).isEqualTo(tiles());
        d.checkConfirmTimeout(3_000 * MS);
        assertThat(d.takeForced().tiles()).isEqualTo(tiles(4));
    }

    @Test
    void unresolvedCapturesCountsCapturesWithLiveRects() {
        long a1 = d.register(0, 10, tiles(1), 100);
        long a2 = d.register(0, 10, tiles(2), 100);
        long b = d.register(0, 11, tiles(3), 100);
        assertThat(d.unresolvedCaptures()).isEqualTo(2);
        assertThat(d.done(a1)).isTrue();
        assertThat(d.done(a1)).isFalse(); // repeated report: no new progress
        assertThat(d.unresolvedCaptures()).isEqualTo(2);
        assertThat(d.dropped(a2)).isTrue();
        assertThat(d.unresolvedCaptures()).isEqualTo(1);
        d.packetSent(b, 7);
        d.packetLost(7);
        assertThat(d.unresolvedCaptures()).isZero();
        d.register(0, 12, tiles(1), 100);
        d.newEpoch(1, grid);
        assertThat(d.unresolvedCaptures()).isZero();
    }

    @Test
    void confirmTimeoutWaitsForAllPackets() {
        long r = d.register(0, 10, tiles(4), 100);
        d.packetSent(r, 1);
        d.packetSent(r, 2);
        d.packetsAcked(List.of(1L), 0);
        d.checkConfirmTimeout(5_000 * MS);
        assertThat(d.takeForced().tiles()).isEqualTo(tiles());
    }

    @Test
    void confirmTimeoutRestartsWhenAnotherPacketIsSent() {
        long r = d.register(0, 10, tiles(4), 100);
        d.packetSent(r, 1);
        d.packetsAcked(List.of(1L), 0);
        d.packetSent(r, 2); // sealed later by the pacer
        d.checkConfirmTimeout(3_000 * MS);
        assertThat(d.takeForced().tiles()).isEqualTo(tiles());
        d.packetsAcked(List.of(2L), 3_000 * MS);
        d.checkConfirmTimeout(4_999 * MS);
        assertThat(d.takeForced().tiles()).isEqualTo(tiles());
        d.checkConfirmTimeout(5_000 * MS);
        assertThat(d.takeForced().tiles()).isEqualTo(tiles(4));
    }

    @Test
    void confirmTimeoutOverflowSafe() {
        long start = Long.MAX_VALUE - 500 * MS;
        long r = d.register(0, 10, tiles(4), 100);
        d.packetSent(r, 1);
        d.packetsAcked(List.of(1L), start);
        d.checkConfirmTimeout(start + 1_900 * MS); // wraps negative
        assertThat(d.takeForced().tiles()).isEqualTo(tiles());
        d.checkConfirmTimeout(start + 2_000 * MS);
        assertThat(d.takeForced().tiles()).isEqualTo(tiles(4));
    }

    @Test
    void severalRectsTimeOutInOneCheck() {
        long r1 = d.register(0, 10, tiles(1), 100);
        long r2 = d.register(0, 10, tiles(2), 100);
        long r3 = d.register(0, 10, tiles(3), 100);
        d.packetSent(r1, 1);
        d.packetSent(r2, 2);
        d.packetSent(r3, 3);
        d.packetsAcked(List.of(1L, 2L, 3L), 0);
        d.done(r2);
        d.checkConfirmTimeout(2_000 * MS);
        assertThat(d.takeForced().tiles()).isEqualTo(tiles(1, 3));
        assertThat(d.unconfirmedBytes()).isZero();
    }

    @Test
    void newEpochForgetsOld() {
        long r = d.register(0, 10, tiles(1), 1000);
        d.packetSent(r, 1);
        d.packetSent(r, 2);
        assertThat(d.unconfirmedBytes()).isEqualTo(1000);
        TileGrid bigger = new TileGrid(640, 480);
        d.newEpoch(1, bigger);
        assertThat(d.epoch()).isEqualTo(1);
        assertThat(d.unconfirmedBytes()).isZero();
        d.packetLost(1);
        d.done(r);
        d.dropped(r);
        d.packetsAcked(List.of(2L), 0);
        d.checkConfirmTimeout(10_000 * MS);
        Forced forced = d.takeForced();
        assertThat(forced.epoch()).isEqualTo(1);
        assertThat(forced.tiles()).isEqualTo(tiles());
        long r2 = d.register(1, 10, bigger.tilesOf(600, 400, 40, 80), 50);
        assertThat(r2).isGreaterThan(r);
        assertThat(d.unconfirmedBytes()).isEqualTo(50);
    }

    @Test
    void newEpochDropsPendingForced() {
        long r = d.register(0, 10, tiles(1), 100);
        d.packetSent(r, 1);
        d.packetLost(1);
        d.newEpoch(1, grid);
        assertThat(d.takeForced().tiles()).isEqualTo(tiles());
    }

    @Test
    void registerWithStaleEpochRejected() {
        d.newEpoch(1, grid);
        assertThatThrownBy(() -> d.register(0, 10, tiles(1), 100)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void registerBeforeFirstEpochRejected() {
        TileDelivery fresh = new TileDelivery();
        assertThatThrownBy(() -> fresh.register(0, 1, tiles(1), 1)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void bytesReleasedOnDoneAndLoss() {
        long r1 = d.register(0, 10, tiles(1), 300);
        long r2 = d.register(0, 10, tiles(2), 200);
        d.packetSent(r1, 1);
        d.packetSent(r2, 2);
        assertThat(d.unconfirmedBytes()).isEqualTo(500);
        d.done(r1);
        assertThat(d.unconfirmedBytes()).isEqualTo(200);
        d.done(r1);
        assertThat(d.unconfirmedBytes()).isEqualTo(200);
        d.packetLost(2);
        assertThat(d.unconfirmedBytes()).isZero();
        d.done(r2);
        d.packetLost(2);
        assertThat(d.unconfirmedBytes()).isZero();
    }

    @Test
    void doneForgetsPacketNumbers() {
        long r = d.register(0, 10, tiles(1), 100);
        d.packetSent(r, 1);
        d.done(r);
        d.packetLost(1);
        assertThat(d.takeForced().tiles()).isEqualTo(tiles());
    }

    @Test
    void registerClearsForcedOfItsTiles() {
        long r1 = d.register(0, 10, tiles(1, 2), 100);
        d.packetSent(r1, 1);
        d.packetLost(1);
        d.register(0, 12, tiles(1), 100);
        assertThat(d.takeForced().tiles()).isEqualTo(tiles(2));
    }

    @Test
    void registerCopiesTiles() {
        BitSet t = tiles(1);
        long r = d.register(0, 10, t, 100);
        t.set(2);
        d.packetSent(r, 1);
        d.packetLost(1);
        assertThat(d.takeForced().tiles()).isEqualTo(tiles(1));
    }

    @Test
    void takeForcedClears() {
        long r = d.register(0, 10, tiles(6), 100);
        d.packetSent(r, 1);
        d.packetLost(1);
        Forced first = d.takeForced();
        assertThat(first.tiles()).isEqualTo(tiles(6));
        assertThat(d.takeForced().tiles()).isEqualTo(tiles());
        assertThat(first.tiles()).isEqualTo(tiles(6)); // returned set not reused
    }
}

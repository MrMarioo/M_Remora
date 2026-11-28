package pl.mtech.mremora.protocol.netty;

import static org.assertj.core.api.Assertions.assertThat;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.socket.DatagramPacket;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ImpairedDatagramHandlerTest {

    private static final InetSocketAddress PEER = new InetSocketAddress("127.0.0.1", 9000);
    private static final InetSocketAddress SELF = new InetSocketAddress("127.0.0.1", 9001);

    private EmbeddedChannel channel;

    @AfterEach
    void tearDown() {
        if (channel != null) {
            channel.finishAndReleaseAll();
        }
    }

    private EmbeddedChannel channelWith(LinkImpairment impairment) {
        channel = new EmbeddedChannel(new ImpairedDatagramHandler(impairment));
        return channel;
    }

    private static LinkImpairment lossy(double loss, double dup, long seed) {
        return new LinkImpairment(loss, dup, Duration.ZERO, Duration.ZERO, false, false, seed);
    }

    private static DatagramPacket packet(int id) {
        ByteBuf buf = Unpooled.buffer(4).writeInt(id);
        return new DatagramPacket(buf, SELF, PEER);
    }

    private static int idOf(DatagramPacket p) {
        return p.content().getInt(p.content().readerIndex());
    }

    private List<Integer> drainInboundIds() {
        List<Integer> ids = new ArrayList<>();
        DatagramPacket p;
        while ((p = channel.readInbound()) != null) {
            ids.add(idOf(p));
            p.release();
        }
        return ids;
    }

    private int countDeliveredOf(LinkImpairment impairment, int n) {
        EmbeddedChannel ch = new EmbeddedChannel(new ImpairedDatagramHandler(impairment));
        for (int i = 0; i < n; i++) {
            ch.writeInbound(packet(i));
        }
        int count = 0;
        DatagramPacket p;
        while ((p = ch.readInbound()) != null) {
            count++;
            p.release();
        }
        ch.finishAndReleaseAll();
        return count;
    }

    @Test
    void noneIsTransparent() {
        channelWith(LinkImpairment.NONE);
        channel.writeInbound(packet(1));
        channel.writeOutbound(packet(2));

        DatagramPacket in = channel.readInbound();
        DatagramPacket out = channel.readOutbound();
        assertThat(idOf(in)).isEqualTo(1);
        assertThat(idOf(out)).isEqualTo(2);
        in.release();
        out.release();
    }

    @Test
    void blockInboundDropsAndReleases() {
        channelWith(new LinkImpairment(0, 0, Duration.ZERO, Duration.ZERO, true, false, 0));
        DatagramPacket p = packet(1);

        channel.writeInbound(p);

        assertThat((Object) channel.readInbound()).isNull();
        assertThat(p.refCnt()).isZero();
    }

    @Test
    void blockOutboundCompletesPromise() {
        channelWith(LinkImpairment.blocked());
        DatagramPacket p = packet(1);

        ChannelFuture f = channel.writeAndFlush(p);

        assertThat(f.isDone()).isTrue();
        assertThat(f.isSuccess()).isTrue();
        assertThat((Object) channel.readOutbound()).isNull();
        assertThat(p.refCnt()).isZero();
    }

    @Test
    void lossRateIsSeeded() {
        LinkImpairment impairment = lossy(0.1, 0, 7);

        int first = countDeliveredOf(impairment, 1000);
        int second = countDeliveredOf(impairment, 1000);

        assertThat(first).isEqualTo(second);
        int lost = 1000 - first;
        assertThat(lost).isBetween(60, 140);
    }

    @Test
    void duplicateDeliversTwice() {
        channelWith(lossy(0, 1.0, 1));
        DatagramPacket p = packet(5);

        channel.writeInbound(p);

        assertThat(drainInboundIds()).containsExactly(5, 5);
        assertThat(p.refCnt()).isZero();
    }

    @Test
    void duplicateOutboundWritesTwice() {
        channelWith(lossy(0, 1.0, 1));

        channel.writeOutbound(packet(6));

        List<Integer> ids = new ArrayList<>();
        DatagramPacket p;
        while ((p = channel.readOutbound()) != null) {
            ids.add(idOf(p));
            p.release();
        }
        assertThat(ids).containsExactly(6, 6);
    }

    @Test
    void delayHoldsUntilTimePasses() {
        channelWith(new LinkImpairment(0, 0, Duration.ofMillis(100), Duration.ZERO, false, false, 0));

        channel.writeInbound(packet(1));
        assertThat((Object) channel.readInbound()).isNull();

        channel.advanceTimeBy(99, TimeUnit.MILLISECONDS);
        channel.runScheduledPendingTasks();
        assertThat((Object) channel.readInbound()).isNull();

        channel.advanceTimeBy(1, TimeUnit.MILLISECONDS);
        channel.runScheduledPendingTasks();
        assertThat(drainInboundIds()).containsExactly(1);
    }

    @Test
    void delayedOutboundIsWrittenAfterTimePasses() {
        channelWith(new LinkImpairment(0, 0, Duration.ofMillis(50), Duration.ZERO, false, false, 0));

        ChannelFuture f = channel.writeAndFlush(packet(3));
        assertThat((Object) channel.readOutbound()).isNull();
        assertThat(f.isDone()).isFalse();

        channel.advanceTimeBy(50, TimeUnit.MILLISECONDS);
        channel.runScheduledPendingTasks();

        DatagramPacket out = channel.readOutbound();
        assertThat(idOf(out)).isEqualTo(3);
        out.release();
        assertThat(f.isSuccess()).isTrue();
    }

    @Test
    void jitterCanReorder() {
        channelWith(new LinkImpairment(0, 0, Duration.ZERO, Duration.ofMillis(100), false, false, 42));
        List<Integer> sent = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            sent.add(i);
            channel.writeInbound(packet(i));
        }

        channel.advanceTimeBy(200, TimeUnit.MILLISECONDS);
        channel.runScheduledPendingTasks();
        List<Integer> received = drainInboundIds();

        assertThat(received).containsExactlyInAnyOrderElementsOf(sent);
        assertThat(received).isNotEqualTo(sent);
    }

    @Test
    void nonDatagramPassesThrough() {
        channelWith(LinkImpairment.blocked());

        channel.writeInbound("hello");
        channel.writeOutbound("world");

        assertThat((Object) channel.readInbound()).isEqualTo("hello");
        assertThat((Object) channel.readOutbound()).isEqualTo("world");
    }

    @Test
    void closingReleasesPacketHeldForDelayedInbound() {
        channelWith(new LinkImpairment(0, 0, Duration.ofSeconds(10), Duration.ZERO, false, false, 0));
        DatagramPacket p = packet(1);
        channel.writeInbound(p);
        assertThat(p.refCnt()).isEqualTo(1);

        channel.finish();

        assertThat(p.refCnt()).isZero();
    }

    @Test
    void closingReleasesPacketAndCompletesPromiseForDelayedOutbound() {
        channelWith(new LinkImpairment(0, 0, Duration.ofSeconds(10), Duration.ZERO, false, false, 0));
        DatagramPacket p = packet(1);
        ChannelFuture f = channel.writeAndFlush(p);
        assertThat(f.isDone()).isFalse();

        channel.finish();

        assertThat(p.refCnt()).isZero();
        assertThat(f.isDone()).isTrue();
    }

    private static DatagramPacket sized(int id, int bytes) {
        ByteBuf buf = Unpooled.buffer(bytes).writeInt(id).writeZero(bytes - 4);
        return new DatagramPacket(buf, SELF, PEER);
    }

    private List<Integer> drainOutboundIds() {
        List<Integer> ids = new ArrayList<>();
        DatagramPacket p;
        while ((p = channel.readOutbound()) != null) {
            ids.add(idOf(p));
            p.release();
        }
        return ids;
    }

    private void advanceMicros(long micros) {
        channel.advanceTimeBy(micros, TimeUnit.MICROSECONDS);
        channel.runScheduledPendingTasks();
    }

    /** Like {@link #channelWith}, with the clock stopped so only {@link #advanceMicros} moves it. */
    private EmbeddedChannel frozenChannelWith(LinkImpairment impairment) {
        channelWith(impairment).freezeTime();
        return channel;
    }

    private static LinkImpairment limited(long bitsPerSecond, Duration delay) {
        return new LinkImpairment(0, 0, delay, Duration.ZERO, false, false, 0, bitsPerSecond);
    }

    /** 1200 B at 2 Mbit/s: 4.8 ms each; the 64 KiB router queue holds 54 of them (64 800 B). */
    @Test
    void bandwidthLimitsRate() {
        frozenChannelWith(limited(2_000_000, Duration.ZERO));
        ImpairedDatagramHandler handler = channel.pipeline().get(ImpairedDatagramHandler.class);
        List<DatagramPacket> sent = new ArrayList<>();
        List<ChannelFuture> futures = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            DatagramPacket p = sized(i, 1200);
            sent.add(p);
            futures.add(channel.writeAndFlush(p));
        }

        assertThat(drainOutboundIds()).isEmpty();
        assertThat(handler.droppedOutbound()).isEqualTo(1000 - 54);
        assertThat(handler.droppedInbound()).isZero();
        assertThat(handler.dropped()).isEqualTo(1000 - 54);
        for (int i = 54; i < 1000; i++) {
            assertThat(sent.get(i).refCnt()).isZero();
            assertThat(futures.get(i).isSuccess()).isTrue();
        }

        advanceMicros(4_799);
        assertThat(drainOutboundIds()).isEmpty();
        advanceMicros(1);
        assertThat(drainOutboundIds()).containsExactly(0);
        advanceMicros(9 * 4_800); // 48 ms: 1200 B · 8 · 10 / 2e6
        assertThat(drainOutboundIds()).hasSize(9).startsWith(1).endsWith(9);
        advanceMicros(44 * 4_800); // 259.2 ms for all 54
        List<Integer> rest = drainOutboundIds();
        assertThat(rest).hasSize(44).startsWith(10).endsWith(53);
        assertThat(futures.get(53).isSuccess()).isTrue();

        // The queue drained: the link accepts again, starting a fresh serialisation slot.
        channel.writeAndFlush(sized(2000, 1200));
        assertThat(handler.droppedOutbound()).isEqualTo(1000 - 54);
        advanceMicros(4_800);
        assertThat(drainOutboundIds()).containsExactly(2000);
    }

    @Test
    void bandwidthIsPerDirectionAndAddsToDelay() {
        frozenChannelWith(limited(2_000_000, Duration.ofMillis(10)));
        ImpairedDatagramHandler handler = channel.pipeline().get(ImpairedDatagramHandler.class);
        for (int i = 0; i < 54; i++) {
            channel.writeInbound(sized(i, 1200));
            channel.writeOutbound(sized(100 + i, 1200));
        }
        channel.writeInbound(sized(99, 1200));
        assertThat(handler.droppedInbound()).isEqualTo(1);
        assertThat(handler.droppedOutbound()).isZero();

        advanceMicros(4_800 + 9_999);
        assertThat(drainInboundIds()).isEmpty();
        advanceMicros(1);
        assertThat(drainInboundIds()).containsExactly(0);
        assertThat(drainOutboundIds()).containsExactly(100);

        advanceMicros(300_000);
        assertThat(drainInboundIds()).hasSize(53);
        assertThat(drainOutboundIds()).hasSize(53);
    }

    @Test
    void dropCounterCountsLossAndBlocking() {
        channelWith(new LinkImpairment(0, 0, Duration.ZERO, Duration.ZERO, true, false, 0));
        ImpairedDatagramHandler handler = channel.pipeline().get(ImpairedDatagramHandler.class);
        channel.writeInbound(packet(1));
        channel.writeInbound(packet(2));
        channel.writeOutbound(packet(3));
        assertThat(handler.droppedInbound()).isEqualTo(2);
        assertThat(handler.droppedOutbound()).isZero();
        assertThat(drainOutboundIds()).containsExactly(3);

        ImpairedDatagramHandler lossy = new ImpairedDatagramHandler(lossy(0.1, 0, 7));
        EmbeddedChannel ch = new EmbeddedChannel(lossy);
        int delivered = 0;
        for (int i = 0; i < 1000; i++) {
            ch.writeOutbound(packet(i));
        }
        DatagramPacket p;
        while ((p = ch.readOutbound()) != null) {
            delivered++;
            p.release();
        }
        ch.finishAndReleaseAll();
        assertThat(lossy.droppedOutbound()).isEqualTo(1000 - delivered).isPositive();
    }

    @Test
    void duplicatesAreNotCountedAsDrops() {
        channelWith(lossy(0, 1.0, 1));
        ImpairedDatagramHandler handler = channel.pipeline().get(ImpairedDatagramHandler.class);

        channel.writeInbound(packet(5));

        assertThat(drainInboundIds()).containsExactly(5, 5);
        assertThat(handler.dropped()).isZero();
    }

    @Test
    void emptyDatagramOccupiesTheLinkForOneByte() {
        frozenChannelWith(limited(8_000, Duration.ZERO)); // 1 byte = 1 ms
        channel.writeInbound(new DatagramPacket(Unpooled.buffer(0), SELF, PEER));
        channel.writeInbound(sized(1, 4));

        advanceMicros(999);
        assertThat(channel.inboundMessages()).isEmpty();
        advanceMicros(1);
        DatagramPacket empty = channel.readInbound();
        assertThat(empty.content().readableBytes()).isZero();
        empty.release();
        advanceMicros(4_000);
        assertThat(drainInboundIds()).containsExactly(1);
    }

    @Test
    void closingReleasesPacketsQueuedBehindBandwidthLimit() {
        frozenChannelWith(limited(2_000_000, Duration.ZERO));
        List<DatagramPacket> queued = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            DatagramPacket p = sized(i, 1200);
            queued.add(p);
            channel.writeInbound(p);
        }

        channel.finish();

        assertThat(queued).allSatisfy(p -> assertThat(p.refCnt()).isZero());
    }
}

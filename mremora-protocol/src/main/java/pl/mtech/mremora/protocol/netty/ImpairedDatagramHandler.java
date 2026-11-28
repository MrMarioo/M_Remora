package pl.mtech.mremora.protocol.netty;

import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.channel.socket.DatagramPacket;
import io.netty.util.ReferenceCountUtil;
import java.nio.channels.ClosedChannelException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Drops, duplicates, delays, rate-limits or blocks {@link DatagramPacket}s in both directions according to a
 * {@link LinkImpairment}. A single seeded {@link Random} makes runs repeatable. Other message types pass through.
 *
 * <p>Order per packet: blocked or lost (dropped), duplicated, then each copy through the direction's bandwidth limit
 * and finally {@code delay + jitter}. The limit models a router: each direction serialises its packets one after
 * another at {@link LinkImpairment#bandwidthBitsPerSecond()} (counting the datagram payload only, no IP/UDP
 * headers; an empty datagram counts as 1 byte); a packet leaves when its last bit has been sent. A packet that
 * would make the bytes still waiting or being sent exceed {@link #QUEUE_BYTES} is dropped (tail drop). Time comes
 * from the channel's {@linkplain io.netty.util.concurrent.EventExecutor#ticker() ticker}, so {@code EmbeddedChannel}
 * tests control it.
 *
 * <p>Dropped packets (blocked, lost, queue overflow; not duplicates) are counted per direction; the counters may be
 * read from any thread.
 */
public final class ImpairedDatagramHandler extends ChannelDuplexHandler {

    /** Router queue of each direction, in payload bytes. */
    public static final int QUEUE_BYTES = 64 * 1024;

    private final LinkImpairment impairment;
    private final Random random;
    /** Packets waiting on a scheduled task; touched only on the event loop. */
    private final Set<Held> held = new LinkedHashSet<>();
    private final RateLimit inboundLimit = new RateLimit();
    private final RateLimit outboundLimit = new RateLimit();
    private final AtomicLong droppedInbound = new AtomicLong();
    private final AtomicLong droppedOutbound = new AtomicLong();

    public ImpairedDatagramHandler(LinkImpairment impairment) {
        this.impairment = impairment;
        this.random = new Random(impairment.seed());
    }

    /** Inbound packets dropped so far (blocked, lost or beyond the queue). */
    public long droppedInbound() {
        return droppedInbound.get();
    }

    /** Outbound packets dropped so far (blocked, lost or beyond the queue); their write promises succeeded. */
    public long droppedOutbound() {
        return droppedOutbound.get();
    }

    /** {@link #droppedInbound()} plus {@link #droppedOutbound()}. */
    public long dropped() {
        return droppedInbound() + droppedOutbound();
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (!(msg instanceof DatagramPacket packet)) {
            super.channelRead(ctx, msg);
            return;
        }
        if (impairment.blockInbound() || lost()) {
            packet.release();
            droppedInbound.incrementAndGet();
            return;
        }
        boolean duplicate = duplicated();
        DatagramPacket copy = duplicate ? packet.retainedDuplicate() : null;
        deliverIn(ctx, packet);
        if (copy != null) {
            deliverIn(ctx, copy);
        }
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        if (!(msg instanceof DatagramPacket packet)) {
            super.write(ctx, msg, promise);
            return;
        }
        if (impairment.blockOutbound() || lost()) {
            packet.release();
            droppedOutbound.incrementAndGet();
            promise.setSuccess();
            return;
        }
        boolean duplicate = duplicated();
        DatagramPacket copy = duplicate ? packet.retainedDuplicate() : null;
        deliverOut(ctx, packet, promise);
        if (copy != null) {
            deliverOut(ctx, copy, ctx.newPromise());
        }
    }

    private boolean lost() {
        return impairment.lossRate() > 0 && random.nextDouble() < impairment.lossRate();
    }

    private boolean duplicated() {
        return impairment.duplicateRate() > 0 && random.nextDouble() < impairment.duplicateRate();
    }

    private long delayNanos() {
        long nanos = impairment.delay().toNanos();
        long jitter = impairment.jitter().toNanos();
        if (jitter > 0) {
            nanos += (long) (random.nextDouble() * jitter);
        }
        return nanos;
    }

    /**
     * Nanoseconds until {@code packet} has passed the direction's bandwidth limit, {@code 0} without a limit, or
     * {@code -1} when the queue is full.
     */
    private long queueNanos(ChannelHandlerContext ctx, RateLimit limit, DatagramPacket packet) {
        long rate = impairment.bandwidthBitsPerSecond();
        if (rate == 0) {
            return 0;
        }
        int bytes = Math.max(1, packet.content().readableBytes()); // an empty datagram still occupies the link
        return limit.enqueue(ctx.executor().ticker().nanoTime(), bytes, rate);
    }

    private void deliverIn(ChannelHandlerContext ctx, DatagramPacket packet) {
        long queued = queueNanos(ctx, inboundLimit, packet);
        if (queued < 0) {
            packet.release();
            droppedInbound.incrementAndGet();
            return;
        }
        long nanos = queued + delayNanos();
        if (nanos == 0) {
            ctx.fireChannelRead(packet);
        } else {
            hold(ctx, packet, null, nanos);
        }
    }

    private void deliverOut(ChannelHandlerContext ctx, DatagramPacket packet, ChannelPromise promise) {
        long queued = queueNanos(ctx, outboundLimit, packet);
        if (queued < 0) {
            packet.release();
            droppedOutbound.incrementAndGet();
            promise.setSuccess();
            return;
        }
        long nanos = queued + delayNanos();
        if (nanos == 0) {
            ctx.write(packet, promise);
        } else {
            hold(ctx, packet, promise, nanos);
        }
    }

    private void hold(ChannelHandlerContext ctx, DatagramPacket packet, ChannelPromise promise, long nanos) {
        Held h = new Held(packet, promise);
        held.add(h);
        h.future = ctx.executor().schedule(() -> {
            held.remove(h);
            if (promise == null) {
                ctx.fireChannelRead(packet);
            } else {
                ctx.writeAndFlush(packet, promise);
            }
        }, nanos, TimeUnit.NANOSECONDS);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        releaseHeld();
        super.channelInactive(ctx);
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) {
        releaseHeld();
    }

    private void releaseHeld() {
        for (Held h : new ArrayList<>(held)) {
            h.future.cancel(false);
            h.packet.release();
            if (h.promise != null) {
                h.promise.tryFailure(new ClosedChannelException());
            }
        }
        held.clear();
    }

    /** Virtual router queue of one direction; event loop only. */
    private static final class RateLimit {

        /** Departure time and size of each packet not yet fully sent, oldest first. */
        private final ArrayDeque<long[]> inQueue = new ArrayDeque<>();
        private long queuedBytes;
        /** When the last accepted packet leaves; meaningful only while {@link #inQueue} is not empty. */
        private long linkFreeAt;

        /** @return nanoseconds from {@code now} until the packet has left, or {@code -1} when it does not fit */
        long enqueue(long now, int bytes, long bitsPerSecond) {
            while (!inQueue.isEmpty() && inQueue.peekFirst()[0] - now <= 0) {
                queuedBytes -= inQueue.pollFirst()[1];
            }
            if (queuedBytes + bytes > QUEUE_BYTES) {
                return -1;
            }
            long start = inQueue.isEmpty() || linkFreeAt - now < 0 ? now : linkFreeAt;
            // bytes <= 65 535 (a datagram), so bytes * 8e9 fits a long.
            long departure = start + Math.ceilDiv(bytes * 8_000_000_000L, bitsPerSecond);
            linkFreeAt = departure;
            inQueue.addLast(new long[] {departure, bytes});
            queuedBytes += bytes;
            return departure - now;
        }
    }

    private static final class Held {
        final DatagramPacket packet;
        final ChannelPromise promise;
        ScheduledFuture<?> future;

        Held(DatagramPacket packet, ChannelPromise promise) {
            this.packet = packet;
            this.promise = promise;
        }
    }
}

package pl.mtech.mremora.agent;

import static org.assertj.core.api.Assertions.assertThat;

import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import pl.mtech.mremora.protocol.RectPixels;
import pl.mtech.mremora.protocol.netty.LinkImpairment;
import pl.mtech.mremora.protocol.udp.AckFrame;
import pl.mtech.mremora.protocol.udp.RangeSet.Range;
import pl.mtech.mremora.protocol.udp.TileDelivery;
import pl.mtech.mremora.protocol.udp.TileFragment;
import pl.mtech.mremora.protocol.udp.UdpImageSender;
import pl.mtech.mremora.protocol.udp.UdpImageSender.OutgoingRect;
import pl.mtech.mremora.protocol.udp.UdpPacket;
import pl.mtech.mremora.protocol.udp.UdpPacket.Direction;
import pl.mtech.mremora.protocol.udp.UdpPacket.Type;
import pl.mtech.mremora.protocol.udp.UdpPeer;

/** {@link UdpImageChannel} over a real loopback socket, against a raw "viewer" speaking {@code TILE} and {@code ACK}. */
class UdpImageChannelTest {

    private static final Duration WAIT = Duration.ofSeconds(5);
    private static final int SESSION = 0x0C0F_FEE0;
    private static final TileDelivery.Forced NONE = new TileDelivery.Forced(0, new BitSet());

    private EventLoopGroup group;
    private UdpEndpoint endpoint;
    private UdpSessionLink link;
    private RawViewer viewer;
    private final AtomicReference<UdpImageStats> stats = new AtomicReference<>(UdpImageStats.ZERO);
    private final CompletableFuture<Void> failed = new CompletableFuture<>();
    private UdpImageChannel channel;

    @BeforeEach
    void setUp() throws Exception {
        group = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
        endpoint = UdpEndpoint.bind(group, 0, LinkImpairment.NONE).orElseThrow();
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        link = new UdpSessionLink(endpoint, SESSION, key, System::nanoTime, Duration.ofSeconds(60), mode -> { });
        onLoop(() -> {
            endpoint.attach(link);
            link.start();
            return null;
        });
        viewer = new RawViewer(key, endpoint.port());
        viewer.send(Type.PROBE, viewer.peer.probe(System.nanoTime()));
        viewer.receiveUntil(p -> p.type() == Type.PROBE); // the link learnt the viewer's address
        channel = onLoop(() -> new UdpImageChannel(link, new UdpImageSender(link.peer(), System.nanoTime()),
                UdpImageStats.ZERO, stats::set, () -> failed.complete(null)));
    }

    @AfterEach
    void tearDown() {
        viewer.close();
        endpoint.close();
        group.shutdownGracefully(0, 1, TimeUnit.SECONDS).syncUninterruptibly();
    }

    @Test
    void fragmentsArriveSigned() throws Exception {
        byte[] data = new byte[5000];
        new Random(7).nextBytes(data);

        channel.submit(new TileFrameEncoder.UdpFrame(0, 64, 64, 0,
                List.of(new OutgoingRect(0, 0, 64, 64, TileFragment.LOSSLESS, data))));

        TreeMap<Integer, byte[]> byOffset = new TreeMap<>();
        while (byOffset.values().stream().mapToInt(b -> b.length).sum() < data.length) {
            // Opened by the viewer's peer: signed with the session key, agent direction, fresh number.
            TileFragment f = viewer.receiveFragment(fr -> true);
            assertThat(f.epoch()).isZero();
            assertThat(f.streamWidth()).isEqualTo(64);
            assertThat(f.rectId()).isEqualTo(1);
            assertThat(f.totalLength()).isEqualTo(data.length);
            byOffset.put(f.offset(), f.data());
        }
        assertThat(byOffset).hasSize(5); // 5000 B in fragments of at most 1134 B
        ByteArrayOutputStream joined = new ByteArrayOutputStream();
        byOffset.values().forEach(joined::writeBytes);
        assertThat(joined.toByteArray()).isEqualTo(data);
        assertThat(failed).isNotDone();
    }

    @Test
    void ackReleasesBudget() throws Exception {
        channel.submit(solidFrame(0, 0, 0x111111));
        channel.submit(solidFrame(1, 64, 0x222222));
        viewer.receiveFragment(f -> f.rectId() == 1);
        viewer.receiveFragment(f -> f.rectId() == 2);

        // Two captures still unresolved: the viewer side of the gate is closed.
        assertThat(poll(() -> !channel.admitsCapture())).isTrue();
        assertThat(channel.takeForcedIfAdmitted()).isEmpty();

        viewer.ack(viewer.received, List.of(new Range(1, 2)), List.of());

        assertThat(poll(channel::admitsCapture)).isTrue();
        Optional<TileDelivery.Forced> admitted = channel.takeForcedIfAdmitted();
        assertThat(admitted).isPresent();
        assertThat(admitted.get().tiles().isEmpty()).isTrue();
    }

    @Test
    void lossLeadsToResendOfCurrentPixels() throws Exception {
        TileFrameEncoder encoder = new TileFrameEncoder(new MutableScreen(columns(512, 64)));
        TileFrameEncoder.UdpFrame first = encoder.nextForUdp(NONE);
        assertThat(first.rects()).hasSize(8);
        channel.submit(first);

        TreeSet<Long> acked = new TreeSet<>();
        long lostNumber = -1;
        for (int i = 0; i < 8; i++) {
            UdpPacket p = viewer.receiveUntil(q -> q.type() == Type.TILE);
            TileFragment f = TileFragment.decode(p.payload()).orElseThrow();
            if (f.x() == 0) {
                lostNumber = p.number(); // the viewer "never got" the leftmost tile
            } else {
                acked.add(p.number());
            }
        }
        assertThat(lostNumber).isPositive();
        viewer.ack(acked, List.of(), List.of()); // 7 newer packets acknowledged: the first one is lost

        TileDelivery.Forced forced = awaitForced();
        assertThat(forced.epoch()).isZero();
        assertThat(forced.tiles().stream().toArray()).containsExactly(0);
        TileFrameEncoder.UdpFrame resend = encoder.nextForUdp(forced);
        assertThat(resend.captureSeq()).isEqualTo(1);
        channel.submit(resend);

        TileFragment again = viewer.receiveFragment(f -> f.captureSeq() == 1);
        assertThat(again.x()).isZero();
        assertThat(again.width()).isEqualTo(64);
        assertThat(again.encoding()).isEqualTo(TileFragment.SOLID);
        assertThat(RectPixels.decodeSolid(again.data()) & 0xFFFFFF).isEqualTo(0x101010); // the current pixels of the tile
        assertThat(poll(() -> stats.get().packetsLost() >= 1 && stats.get().tilesForced() >= 1)).isTrue();
    }

    @Test
    void resizeStartsTheNewEpochBeforeItsFirstSubmit() throws Exception {
        channel.submit(solidFrame(0, 0, 0x111111));
        channel.submit(new TileFrameEncoder.UdpFrame(1, 256, 64, 1,
                List.of(new OutgoingRect(192, 0, 64, 64, TileFragment.SOLID, RectPixels.encodeSolid(0x333333)))));

        TileFragment f = viewer.receiveFragment(fr -> fr.epoch() == 1);

        assertThat(f.streamWidth()).isEqualTo(256);
        assertThat(f.x()).isEqualTo(192);
        assertThat(onLoop(channel::ignoredSubmits)).isZero();
        assertThat(failed).isNotDone();
    }

    @Test
    void statsArePublishedFromTheSender() throws Exception {
        channel.submit(solidFrame(0, 0, 0x111111));
        viewer.receiveFragment(f -> true);

        assertThat(poll(() -> stats.get().packetsSent() >= 1)).isTrue();
        UdpImageStats s = stats.get();
        assertThat(s.rectsSent()).isEqualTo(1);
        assertThat(s.fragmentsSent()).isEqualTo(1);
        assertThat(s.maxFragmentsPerRect()).isEqualTo(1);
        assertThat(s.rateBitsPerSecond()).isPositive();
    }

    @Test
    void gateStaysClosedWhileSubmitsAreQueued() throws Exception {
        // Loop tasks run in order: [hold, submit 1, probe, submit 2]. The probe sees the gate after the loop applied
        // only the first capture (which alone would admit another); the second is still queued.
        CountDownLatch release = new CountDownLatch(1);
        endpoint.eventLoop().execute(() -> {
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        channel.submit(solidFrame(0, 0, 0x111111));
        CompletableFuture<Boolean> betweenSubmits = new CompletableFuture<>();
        endpoint.eventLoop().execute(() -> betweenSubmits.complete(channel.admitsCapture()));
        channel.submit(solidFrame(1, 64, 0x222222));

        release.countDown();

        assertThat(betweenSubmits.get(WAIT.toMillis(), TimeUnit.MILLISECONDS)).isFalse();
        assertThat(onLoop(channel::admitsCapture)).isFalse(); // both applied: two unresolved captures
    }

    @Test
    void closingTheLinkReleasesAWaitingCaptureThread() throws Exception {
        channel.submit(solidFrame(0, 0, 0x111111));
        channel.submit(solidFrame(1, 64, 0x222222));
        assertThat(poll(() -> !channel.admitsCapture())).isTrue();
        CompletableFuture<Boolean> waited = new CompletableFuture<>();
        Thread capture = Thread.ofPlatform().start(() -> {
            try {
                waited.complete(channel.awaitAdmits());
            } catch (InterruptedException e) {
                waited.completeExceptionally(e);
            }
        });
        assertThat(poll(() -> capture.getState() == Thread.State.WAITING)).isTrue();

        endpoint.detach(link); // the session's end path: closes the link and with it the channel

        assertThat(waited.get(WAIT.toMillis(), TimeUnit.MILLISECONDS)).isFalse();
        capture.join(WAIT.toMillis());
        assertThat(capture.isAlive()).isFalse();
        assertThat(channel.takeForcedIfAdmitted()).isEmpty();
        assertThat(channel.isClosed()).isTrue();
    }

    @Test
    void nothingIsSentAfterClose() throws Exception {
        channel.close();
        channel.submit(solidFrame(0, 0, 0x111111));

        List<UdpPacket> got = new ArrayList<>();
        long deadline = System.nanoTime() + Duration.ofMillis(500).toNanos();
        Optional<UdpPacket> p;
        while ((p = viewer.receive(Duration.ofNanos(Math.max(0, deadline - System.nanoTime())))).isPresent()) {
            got.add(p.get());
        }
        assertThat(got).noneMatch(q -> q.type() == Type.TILE);
    }

    @Test
    void ackOnALinkWithoutImageIsIgnored() throws Exception {
        byte[] key = new byte[32];
        UdpSessionLink bare = new UdpSessionLink(endpoint, SESSION + 1, key, System::nanoTime,
                Duration.ofSeconds(60), mode -> { });
        UdpPacket ack = new UdpPacket(Type.ACK, Direction.VIEWER, SESSION + 1, 1,
                new AckFrame(0, List.of(new Range(1, 1)), List.of(new Range(1, 1)), List.of()).encode());
        UdpImageStats before = stats.get();
        onLoop(() -> {
            endpoint.detach(link);
            endpoint.attach(bare);
            bare.start();
            bare.received(ack, viewer.address()); // a TCP-mode session: no image channel to hand it to
            return null;
        });
        // Handled without a throw (it taught the link the viewer's address) and reached no image channel.
        assertThat(onLoop(bare::viewerAddress)).contains(viewer.address());
        onLoop(() -> {
            endpoint.detach(bare);
            bare.received(ack, viewer.address()); // an ended session
            return null;
        });
        assertThat(onLoop(bare::timersActive)).isFalse();
        assertThat(stats.get()).isEqualTo(before);
        assertThat(onLoop(channel::ignoredSubmits)).isZero();
        assertThat(failed).isNotDone();
    }

    @Test
    void persistentPollFailureEndsTheSession() throws Exception {
        AtomicInteger polls = new AtomicInteger();
        faults(new UdpImageChannel.Faults() {
            @Override
            public void beforePoll() {
                polls.incrementAndGet();
                throw new IllegalStateException("injected poll failure");
            }
        });

        channel.submit(solidFrame(0, 0, 0x111111));

        failed.get(WAIT.toMillis(), TimeUnit.MILLISECONDS); // retried every 25 ms, failed on the third
        assertThat(channel.isClosed()).isTrue();
        assertThat(polls.get()).isEqualTo(UdpImageChannel.MAX_CONSECUTIVE_FAILURES);
        Thread.sleep(200); // ~8 retry periods: no timer is left
        assertThat(polls.get()).isEqualTo(UdpImageChannel.MAX_CONSECUTIVE_FAILURES);
    }

    @Test
    void pollFailuresBelowTheLimitAreRetried() throws Exception {
        AtomicInteger polls = new AtomicInteger();
        faults(new UdpImageChannel.Faults() {
            @Override
            public void beforePoll() {
                if (polls.incrementAndGet() < UdpImageChannel.MAX_CONSECUTIVE_FAILURES) {
                    throw new IllegalStateException("injected poll failure " + polls.get());
                }
            }
        });

        channel.submit(solidFrame(0, 0, 0x111111));

        TileFragment f = viewer.receiveFragment(fr -> true); // sent by the retry that succeeded
        assertThat(f.rectId()).isEqualTo(1);
        assertThat(polls.get()).isGreaterThanOrEqualTo(UdpImageChannel.MAX_CONSECUTIVE_FAILURES);
        assertThat(failed).isNotDone();
        assertThat(channel.isClosed()).isFalse();
    }

    @Test
    void packetFailureStillPumps() throws Exception {
        List<String> events = new CopyOnWriteArrayList<>();
        AtomicInteger packets = new AtomicInteger();
        faults(new UdpImageChannel.Faults() {
            @Override
            public void beforePoll() {
                events.add("poll");
            }

            @Override
            public void beforePacket() {
                events.add("packet");
                if (packets.incrementAndGet() == 1) {
                    throw new IllegalStateException("injected packet failure");
                }
            }
        });
        channel.submit(solidFrame(0, 0, 0x111111));
        channel.submit(solidFrame(1, 64, 0x222222));
        viewer.receiveFragment(f -> f.rectId() == 1);
        viewer.receiveFragment(f -> f.rectId() == 2);
        assertThat(poll(() -> !channel.admitsCapture())).isTrue(); // two unresolved captures

        viewer.ack(viewer.received, List.of(new Range(1, 2)), List.of()); // its handling throws
        assertThat(poll(() -> packets.get() >= 1)).isTrue();
        int thrown = onLoop(() -> events.indexOf("packet"));
        assertThat(events.get(thrown + 1)).as("pumped right after the failed packet").isEqualTo("poll");
        assertThat(onLoop(channel::admitsCapture)).as("the failed ACK resolved nothing").isFalse();

        viewer.ack(viewer.received, List.of(new Range(1, 2)), List.of()); // the next one is handled
        assertThat(poll(channel::admitsCapture)).isTrue();
        assertThat(failed).isNotDone();
    }

    @Test
    void persistentPacketFailureEndsTheSession() throws Exception {
        AtomicInteger packets = new AtomicInteger();
        faults(new UdpImageChannel.Faults() {
            @Override
            public void beforePacket() {
                packets.incrementAndGet();
                throw new IllegalStateException("injected packet failure");
            }
        });
        channel.submit(solidFrame(0, 0, 0x111111));
        viewer.receiveFragment(f -> true);

        for (int i = 0; i < UdpImageChannel.MAX_CONSECUTIVE_FAILURES; i++) {
            viewer.ack(viewer.received, List.of(), List.of());
        }

        failed.get(WAIT.toMillis(), TimeUnit.MILLISECONDS);
        assertThat(channel.isClosed()).isTrue();
        assertThat(packets.get()).isEqualTo(UdpImageChannel.MAX_CONSECUTIVE_FAILURES);
    }

    // --- helpers ---

    private void faults(UdpImageChannel.Faults f) throws Exception {
        onLoop(() -> {
            channel.faults(f);
            return null;
        });
    }

    private static TileFrameEncoder.UdpFrame solidFrame(long seq, int x, int rgb) {
        return new TileFrameEncoder.UdpFrame(0, 256, 64, seq,
                List.of(new OutgoingRect(x, 0, 64, 64, TileFragment.SOLID, RectPixels.encodeSolid(rgb))));
    }

    /** Distinct solid colour per 64 px column. */
    private static BufferedImage columns(int w, int h) {
        var img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                img.setRGB(x, y, 0x101010 * (x / 64 + 1));
            }
        }
        return img;
    }

    private TileDelivery.Forced awaitForced() throws InterruptedException {
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (System.nanoTime() - deadline < 0) {
            Optional<TileDelivery.Forced> f = channel.takeForcedIfAdmitted();
            if (f.isPresent() && !f.get().tiles().isEmpty()) {
                return f.get();
            }
            Thread.sleep(10);
        }
        throw new AssertionError("no forced tiles within " + WAIT);
    }

    private <T> T onLoop(Callable<T> task) throws Exception {
        return endpoint.eventLoop().submit(task).get(WAIT.toMillis(), TimeUnit.MILLISECONDS);
    }

    private static boolean poll(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() - deadline >= 0) {
                return false;
            }
            Thread.sleep(10);
        }
        return true;
    }

    /** A raw viewer on loopback: its own {@link UdpPeer}, remembers the numbers of the packets it opened. */
    private static final class RawViewer {
        final UdpPeer peer;
        final DatagramSocket socket;
        final InetSocketAddress agent;
        final TreeSet<Long> received = new TreeSet<>();

        RawViewer(byte[] key, int port) throws IOException {
            peer = new UdpPeer(Direction.VIEWER, SESSION, key);
            socket = new DatagramSocket(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            agent = new InetSocketAddress(InetAddress.getLoopbackAddress(), port);
        }

        InetSocketAddress address() {
            return (InetSocketAddress) socket.getLocalSocketAddress();
        }

        /** Sends a sealed datagram ({@code wire} already sealed by {@link #peer}). */
        void send(Type type, byte[] wire) throws IOException {
            socket.send(new DatagramPacket(wire, wire.length, agent));
        }

        /** ACKs the packet numbers in {@code numbers} (merged into ranges, newest first). */
        void ack(TreeSet<Long> numbers, List<Range> done, List<Long> dropped) throws IOException {
            List<Range> ranges = new ArrayList<>();
            Long from = null;
            Long to = null;
            for (long n : numbers.descendingSet()) {
                if (to == null) {
                    from = n;
                    to = n;
                } else if (n == from - 1) {
                    from = n;
                } else {
                    ranges.add(new Range(from, to));
                    from = n;
                    to = n;
                }
            }
            if (to != null) {
                ranges.add(new Range(from, to));
            }
            byte[] payload = new AckFrame(0, ranges, done, dropped).encode();
            send(Type.ACK, peer.seal(Type.ACK, payload));
        }

        /** Next opened agent packet within {@code within}; anything that does not open is skipped. */
        Optional<UdpPacket> receive(Duration within) throws IOException {
            long deadline = System.nanoTime() + within.toNanos();
            while (true) {
                long left = deadline - System.nanoTime();
                if (left <= 0) {
                    return Optional.empty();
                }
                byte[] buf = new byte[2048];
                DatagramPacket dp = new DatagramPacket(buf, buf.length);
                socket.setSoTimeout((int) Math.max(1, left / 1_000_000));
                try {
                    socket.receive(dp);
                } catch (SocketTimeoutException e) {
                    return Optional.empty();
                }
                Optional<UdpPacket> p = peer.open(ByteBuffer.wrap(buf, 0, dp.getLength()));
                if (p.isPresent()) {
                    received.add(p.get().number());
                    return p;
                }
            }
        }

        UdpPacket receiveUntil(Predicate<UdpPacket> wanted) throws IOException {
            long deadline = System.nanoTime() + WAIT.toNanos();
            while (true) {
                Optional<UdpPacket> p = receive(Duration.ofNanos(Math.max(0, deadline - System.nanoTime())));
                if (p.isEmpty()) {
                    throw new AssertionError("wanted packet did not arrive within " + WAIT);
                }
                if (wanted.test(p.get())) {
                    return p.get();
                }
            }
        }

        TileFragment receiveFragment(Predicate<TileFragment> wanted) throws IOException {
            while (true) {
                UdpPacket p = receiveUntil(q -> q.type() == Type.TILE);
                TileFragment f = TileFragment.decode(p.payload()).orElseThrow();
                if (wanted.test(f)) {
                    return f;
                }
            }
        }

        void close() {
            socket.close();
        }
    }
}

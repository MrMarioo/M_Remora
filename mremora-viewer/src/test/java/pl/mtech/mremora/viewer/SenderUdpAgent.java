package pl.mtech.mremora.viewer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import pl.mtech.mremora.protocol.UdpSetup;
import pl.mtech.mremora.protocol.udp.TileFragment;
import pl.mtech.mremora.protocol.udp.UdpImageSender;
import pl.mtech.mremora.protocol.udp.UdpPacket;
import pl.mtech.mremora.protocol.udp.UdpPacket.Direction;
import pl.mtech.mremora.protocol.udp.UdpPeer;

/**
 * The agent's UDP end for viewer tests while the production agent still sends the picture over TCP: a raw loopback
 * socket driving the real {@link UdpImageSender} core. The test announces {@link #setup()} over TCP, then
 * {@link #pumpUntil pumps} on its own thread. Test thread only.
 */
final class SenderUdpAgent implements AutoCloseable {

    private static final int SESSION_ID = 0x0BAD_CAFE;

    private final byte[] key = new byte[UdpSetup.KEY_LENGTH];
    private final DatagramSocket socket;
    private final UdpPeer peer;
    private final UdpImageSender sender;
    /** The viewer's address, learnt from its first valid packet. */
    private SocketAddress viewer;

    SenderUdpAgent() {
        Arrays.fill(key, (byte) 0x5A);
        try {
            socket = new DatagramSocket(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        peer = new UdpPeer(Direction.AGENT, SESSION_ID, key);
        sender = new UdpImageSender(peer, System.nanoTime());
    }

    /** The {@code UdpSetup} to send to the viewer over TCP. */
    UdpSetup setup() {
        return new UdpSetup(socket.getLocalPort(), SESSION_ID, key);
    }

    UdpImageSender sender() {
        return sender;
    }

    /**
     * Sends whatever the sender has, feeds it the viewer's packets (answering probes) until {@code done} holds;
     * nothing is sent before the viewer's first packet told its address.
     *
     * @return whether {@code done} held within {@code timeout}
     */
    boolean pumpUntil(BooleanSupplier done, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!done.getAsBoolean()) {
            long now = System.nanoTime();
            if (now - deadline >= 0) {
                return false;
            }
            if (viewer != null) {
                for (byte[] datagram : sender.poll(now)) {
                    send(datagram);
                }
            }
            long waitNanos = Math.min(sender.nextDeadlineNanos(now) - now, 5_000_000L);
            receive(Math.max(1, waitNanos / 1_000_000)).ifPresent(p -> {
                peer.answer(p).ifPresent(this::send);
                sender.onPacket(p, System.nanoTime());
            });
        }
        return true;
    }

    private Optional<UdpPacket> receive(long timeoutMillis) {
        byte[] buf = new byte[2048];
        DatagramPacket dp = new DatagramPacket(buf, buf.length);
        try {
            socket.setSoTimeout((int) timeoutMillis);
            socket.receive(dp);
        } catch (SocketTimeoutException e) {
            return Optional.empty();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        Optional<UdpPacket> p = peer.open(ByteBuffer.wrap(buf, 0, dp.getLength()));
        if (p.isPresent()) {
            viewer = dp.getSocketAddress();
        }
        return p;
    }

    private void send(byte[] datagram) {
        try {
            socket.send(new DatagramPacket(datagram, datagram.length, viewer));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** One-rect capture helper: the whole stream in one solid colour. */
    static List<UdpImageSender.OutgoingRect> solidStream(int width, int height, byte[] solid) {
        return List.of(new UdpImageSender.OutgoingRect(0, 0, width, height,
                TileFragment.SOLID, solid));
    }

    @Override
    public void close() {
        socket.close();
    }
}

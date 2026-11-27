package pl.mtech.mremora.testkit;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import pl.mtech.mremora.protocol.Message;
import pl.mtech.mremora.protocol.MessageCodec;

/**
 * Blocking test client that speaks the MRemora wire format over a plain {@link Socket} to loopback.
 * Not thread-safe: use it from the test thread only.
 */
public final class WireClient implements AutoCloseable {

    private static final int READ_CHUNK = 64 * 1024;

    private final MessageCodec codec = new MessageCodec();
    private final Socket socket;
    private final InputStream in;
    private final OutputStream out;
    /** Received bytes not yet decoded, in {@code [0, filled)}. */
    private byte[] pending = new byte[READ_CHUNK];
    private int filled;

    private WireClient(Socket socket) throws IOException {
        this.socket = socket;
        this.in = socket.getInputStream();
        this.out = socket.getOutputStream();
    }

    /** Connects to {@code port} on the loopback address. */
    public static WireClient connect(int port) {
        try {
            Socket socket = new Socket(InetAddress.getLoopbackAddress(), port);
            socket.setTcpNoDelay(true);
            return new WireClient(socket);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Waits for one connection on {@code server} and speaks the wire format over it, for tests that play the
     * server side (e.g. a fake agent).
     */
    public static WireClient accept(ServerSocket server) {
        try {
            Socket socket = server.accept();
            socket.setTcpNoDelay(true);
            return new WireClient(socket);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Encodes and sends one message. */
    public void send(Message m) {
        ByteBuffer frame = codec.encode(m);
        byte[] bytes = new byte[frame.remaining()];
        frame.get(bytes);
        sendRaw(bytes);
    }

    /** Sends bytes as they are, without framing. */
    public void sendRaw(byte[] bytes) {
        try {
            out.write(bytes);
            out.flush();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Returns the next message, heartbeats included.
     *
     * @throws AssertionError when no complete message arrives within {@code timeout}, or the server closed
     */
    public Message receive(Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (true) {
            Optional<Message> message = decodePending();
            if (message.isPresent()) {
                return message.get();
            }
            long left = deadline - System.nanoTime();
            if (left <= 0) {
                throw new AssertionError("No message within " + timeout);
            }
            int n;
            try {
                n = readWithTimeout(left);
            } catch (SocketTimeoutException e) {
                throw new AssertionError("No message within " + timeout, e);
            } catch (IOException e) {
                throw new AssertionError("Connection failed while waiting for a message", e);
            }
            if (n < 0) {
                throw new AssertionError("Server closed the connection while waiting for a message");
            }
        }
    }

    /**
     * Reads and discards everything until the server closes the connection.
     *
     * @return {@code true} on end of stream or connection reset, {@code false} when still open after {@code timeout}
     */
    public boolean awaitClosed(Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (true) {
            long left = deadline - System.nanoTime();
            if (left <= 0) {
                return false;
            }
            try {
                if (readWithTimeout(left) < 0) {
                    return true;
                }
                filled = 0;
            } catch (SocketTimeoutException e) {
                return false;
            } catch (IOException e) {
                return true;
            }
        }
    }

    /**
     * Returns every message, heartbeats included, until the server closes the connection.
     *
     * @throws AssertionError when the connection is still open after {@code timeout}
     */
    public List<Message> receiveUntilClosed(Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        List<Message> messages = new ArrayList<>();
        while (true) {
            Optional<Message> message = decodePending();
            if (message.isPresent()) {
                messages.add(message.get());
                continue;
            }
            long left = deadline - System.nanoTime();
            if (left <= 0) {
                throw new AssertionError("Connection still open after " + timeout + "; received " + messages.size());
            }
            try {
                if (readWithTimeout(left) < 0) {
                    return messages;
                }
            } catch (SocketTimeoutException e) {
                throw new AssertionError("Connection still open after " + timeout + "; received " + messages.size(), e);
            } catch (IOException e) {
                return messages; // reset: closed
            }
        }
    }

    /**
     * Marks that the test deliberately stops reading: the client simply does not call {@link #receive}, so the
     * server's writes back up in the TCP buffers.
     */
    public void pauseReading() {
        // Nothing to do: not reading is the behavior.
    }

    @Override
    public void close() {
        try {
            socket.close();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private Optional<Message> decodePending() {
        ByteBuffer view = ByteBuffer.wrap(pending, 0, filled);
        Optional<Message> message = codec.decode(view, MessageCodec.MAX_FRAME_LENGTH);
        if (message.isPresent()) {
            int consumed = view.position();
            System.arraycopy(pending, consumed, pending, 0, filled - consumed);
            filled -= consumed;
        }
        return message;
    }

    private int readWithTimeout(long timeoutNanos) throws IOException {
        socket.setSoTimeout((int) Math.max(1, Math.min(Integer.MAX_VALUE, timeoutNanos / 1_000_000)));
        if (pending.length - filled < READ_CHUNK) {
            pending = Arrays.copyOf(pending, Math.max(pending.length * 2, filled + READ_CHUNK));
        }
        int n = in.read(pending, filled, READ_CHUNK);
        if (n > 0) {
            filled += n;
        }
        return n;
    }
}

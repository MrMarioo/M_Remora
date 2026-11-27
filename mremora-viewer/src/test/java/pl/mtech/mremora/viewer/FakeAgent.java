package pl.mtech.mremora.viewer;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.time.Duration;
import pl.mtech.mremora.protocol.Hello;
import pl.mtech.mremora.protocol.Message;
import pl.mtech.mremora.protocol.ScreenInfo;
import pl.mtech.mremora.testkit.WireClient;

/**
 * A scripted agent on loopback for viewer tests that need exact control of the wire: accepts one viewer, answers
 * its {@code Hello} with a chosen version, then the test sends and receives raw messages. Test thread only.
 */
final class FakeAgent implements AutoCloseable {

    private final ServerSocket server;
    private WireClient wire;

    FakeAgent() {
        try {
            server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    int port() {
        return server.getLocalPort();
    }

    /**
     * Accepts the viewer, checks its {@code Hello} and completes the handshake with {@code version} and
     * {@code screen}.
     *
     * @return the viewer's {@code Hello}
     */
    Hello handshake(int version, ScreenInfo screen, Duration timeout) {
        try {
            server.setSoTimeout((int) timeout.toMillis());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        wire = WireClient.accept(server);
        Message first = wire.receive(timeout);
        assertThat(first).isInstanceOf(Hello.class);
        Hello hello = (Hello) first;
        assertThat(hello.role()).isEqualTo(Hello.Role.VIEWER);
        wire.send(new Hello(version, Hello.Role.AGENT, ""));
        wire.send(screen);
        return hello;
    }

    /** The connection to the viewer; valid after {@link #handshake}. */
    WireClient wire() {
        return wire;
    }

    @Override
    public void close() throws IOException {
        if (wire != null) {
            wire.close();
        }
        server.close();
    }
}

package pl.mtech.mremora.agent;

import java.time.Duration;
import java.util.Objects;

/**
 * Network and timing settings of the agent.
 *
 * @param port              TCP port to listen on, {@code 0} for any free port
 * @param handshakeTimeout  time a new connection has to send its {@code Hello}
 * @param heartbeatInterval write silence after which a heartbeat is sent
 * @param idleTimeout       read silence after which the connection is closed
 * @param maxFps            upper bound of captured frames per second
 */
public record AgentConfig(int port, Duration handshakeTimeout, Duration heartbeatInterval,
                          Duration idleTimeout, int maxFps) {

    public static final int DEFAULT_PORT = 7420;

    public AgentConfig {
        if (port < 0 || port > 0xFFFF) {
            throw new IllegalArgumentException("port must be 0..65535, was " + port);
        }
        requirePositive(handshakeTimeout, "handshakeTimeout");
        requirePositive(heartbeatInterval, "heartbeatInterval");
        requirePositive(idleTimeout, "idleTimeout");
        if (maxFps <= 0) {
            throw new IllegalArgumentException("maxFps must be positive, was " + maxFps);
        }
    }

    /** Stage 1 defaults on the given port. */
    public static AgentConfig defaults(int port) {
        return new AgentConfig(port, Duration.ofSeconds(5), Duration.ofSeconds(2), Duration.ofSeconds(6), 30);
    }

    private static void requirePositive(Duration d, String name) {
        Objects.requireNonNull(d, name);
        if (d.isNegative() || d.isZero()) {
            throw new IllegalArgumentException(name + " must be positive, was " + d);
        }
    }
}

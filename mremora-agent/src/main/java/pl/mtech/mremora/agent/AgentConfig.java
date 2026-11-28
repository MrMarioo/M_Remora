package pl.mtech.mremora.agent;

import java.time.Duration;
import java.util.Objects;
import pl.mtech.mremora.protocol.Hello;

/**
 * Network and timing settings of the agent.
 *
 * @param port              TCP port to listen on, {@code 0} for any free port
 * @param handshakeTimeout  time a new connection has to send its {@code Hello}
 * @param heartbeatInterval write silence after which a heartbeat is sent
 * @param idleTimeout       read silence after which the connection is closed
 * @param maxFps            upper bound of captured frames per second
 * @param maxProtocolVersion highest protocol version offered to viewers, {@code 1..}{@link #HIGHEST_PROTOCOL_VERSION}
 */
public record AgentConfig(int port, Duration handshakeTimeout, Duration heartbeatInterval,
                          Duration idleTimeout, int maxFps, int maxProtocolVersion) {

    public static final int DEFAULT_PORT = 7420;

    /** Highest protocol version this agent can speak (v3: UDP setup, picture still over TCP), whatever {@link Hello#PROTOCOL_VERSION} says. */
    public static final int HIGHEST_PROTOCOL_VERSION = 3;

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
        if (maxProtocolVersion < 1 || maxProtocolVersion > HIGHEST_PROTOCOL_VERSION) {
            throw new IllegalArgumentException(
                    "maxProtocolVersion must be 1.." + HIGHEST_PROTOCOL_VERSION + ", was " + maxProtocolVersion);
        }
    }

    /**
     * Protocol version offered by {@link #defaults}: {@link Hello#PROTOCOL_VERSION}, capped at what this agent
     * implements, so bumping the wire constant alone never makes the defaults invalid.
     */
    public static final int DEFAULT_PROTOCOL_VERSION = Math.min(Hello.PROTOCOL_VERSION, HIGHEST_PROTOCOL_VERSION);

    /** Stage 1 defaults on the given port, offering {@link #DEFAULT_PROTOCOL_VERSION}. */
    public static AgentConfig defaults(int port) {
        return new AgentConfig(port, Duration.ofSeconds(5), Duration.ofSeconds(2), Duration.ofSeconds(6), 30,
                DEFAULT_PROTOCOL_VERSION);
    }

    /** A copy offering {@code version} instead (e.g. the developer opt-in {@code mremora.dev.protocolVersion}). */
    public AgentConfig withMaxProtocolVersion(int version) {
        return new AgentConfig(port, handshakeTimeout, heartbeatInterval, idleTimeout, maxFps, version);
    }

    private static void requirePositive(Duration d, String name) {
        Objects.requireNonNull(d, name);
        if (d.isNegative() || d.isZero()) {
            throw new IllegalArgumentException(name + " must be positive, was " + d);
        }
    }
}

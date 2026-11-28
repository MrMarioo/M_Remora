package pl.mtech.mremora.protocol.udp;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import pl.mtech.mremora.protocol.udp.UdpPacket.Direction;
import pl.mtech.mremora.protocol.udp.UdpPacket.Type;

/**
 * One side ({@code self}) of the signed UDP channel of a session: signs and numbers outbound packets, verifies
 * inbound ones (sent by the other side) against the codec and the replay window, answers probes and measures the
 * round trip of its own probes. The part of the channel both the agent and the viewer share; timers, sockets and
 * addresses stay with the caller. Single-threaded (the codec's {@code Mac} and the window are not thread-safe).
 */
public final class UdpPeer {

    /** Size of the replay window of the other side's packet numbers. */
    public static final int REPLAY_WINDOW = 1024;
    /** Probe interval until the channel is proven (agent: until the decision; viewer: until the first reply). */
    public static final Duration PROBE_FAST = Duration.ofMillis(200);
    /** Probe interval afterwards: NAT keepalive. */
    public static final Duration PROBE_SLOW = Duration.ofSeconds(1);
    /** Payload length of a {@code PROBE}. */
    public static final int PROBE_LENGTH = 8;
    /** Payload length of a {@code PROBE_REPLY}: just the echoed {@code sentNanos}. */
    public static final int REPLY_LENGTH = 8;

    private final Direction self;
    private final Direction other;
    private final int sessionId;
    private final UdpPacketCodec codec;
    private final ReplayWindow window = new ReplayWindow(REPLAY_WINDOW);

    private long nextNumber = 1;
    /** {@code sentNanos} range of the probes sent so far; a reply echoing anything else is ignored. */
    private boolean probed;
    private long firstProbeNanos;
    private long lastProbeNanos;

    /** @param key the 32-byte session key (copied by the codec) */
    public UdpPeer(Direction self, int sessionId, byte[] key) {
        this.self = Objects.requireNonNull(self, "self");
        this.other = self == Direction.AGENT ? Direction.VIEWER : Direction.AGENT;
        this.sessionId = sessionId;
        this.codec = new UdpPacketCodec(Objects.requireNonNull(key, "key"));
    }

    public int sessionId() {
        return sessionId;
    }

    /**
     * Verifies one inbound datagram: a valid packet of this session sent by the other side, not seen before.
     * Only such a packet moves the replay window. Never throws for malformed input.
     *
     * @return empty when the datagram is to be dropped
     */
    public Optional<UdpPacket> open(ByteBuffer datagram) {
        Optional<UdpPacket> decoded = codec.decode(datagram, sessionId, other);
        if (decoded.isEmpty()) {
            return Optional.empty();
        }
        UdpPacket p = decoded.get();
        if (!window.isFresh(p.number())) {
            return Optional.empty();
        }
        window.accept(p.number());
        return decoded;
    }

    /**
     * The packet number given by the most recent {@link #seal} (also through {@link #probe} and {@link #answer});
     * {@code 0} before the first.
     */
    public long lastSealedNumber() {
        return nextNumber - 1;
    }

    /** Signs a packet of {@code type} from this side with the next packet number (first {@code 1}). */
    public byte[] seal(Type type, byte[] payload) {
        return codec.encode(new UdpPacket(type, self, sessionId, nextNumber++, payload));
    }

    /** A new {@code PROBE} stamped with {@code nowNanos} ({@link System#nanoTime()}); remembered for {@link #rttOf}. */
    public byte[] probe(long nowNanos) {
        if (!probed) {
            firstProbeNanos = nowNanos;
            probed = true;
        }
        lastProbeNanos = nowNanos;
        return seal(Type.PROBE, ProbePayload.encode(nowNanos, PROBE_LENGTH));
    }

    /** The {@code PROBE_REPLY} to an opened {@code PROBE}; empty for any other packet or a too short payload. */
    public Optional<byte[]> answer(UdpPacket probe) {
        if (probe.type() != Type.PROBE) {
            return Optional.empty();
        }
        OptionalLong sent = ProbePayload.sentNanos(probe.payload());
        if (sent.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(seal(Type.PROBE_REPLY, ProbePayload.encode(sent.getAsLong(), REPLY_LENGTH)));
    }

    /**
     * Round-trip time proven by an opened {@code PROBE_REPLY}: empty unless it echoes the time of one of this side's
     * own probes (anything else proves nothing about the way there).
     */
    public OptionalLong rttOf(UdpPacket reply, long nowNanos) {
        if (reply.type() != Type.PROBE_REPLY) {
            return OptionalLong.empty();
        }
        OptionalLong echoed = ProbePayload.sentNanos(reply.payload());
        if (echoed.isEmpty() || !probed) {
            return OptionalLong.empty();
        }
        long sent = echoed.getAsLong();
        if (sent - firstProbeNanos < 0 || lastProbeNanos - sent < 0) {
            return OptionalLong.empty();
        }
        return OptionalLong.of(nowNanos - sent);
    }
}

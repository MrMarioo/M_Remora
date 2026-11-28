package pl.mtech.mremora.protocol.udp;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import pl.mtech.mremora.protocol.udp.UdpPacket.Direction;
import pl.mtech.mremora.protocol.udp.UdpPacket.Type;

class UdpPeerTest {

    private static final int SESSION = 0x0BAD_CAFE;

    private final byte[] key = key(1);
    private final UdpPeer agent = new UdpPeer(Direction.AGENT, SESSION, key);
    private final UdpPeer viewer = new UdpPeer(Direction.VIEWER, SESSION, key);

    private static byte[] key(int seed) {
        byte[] k = new byte[32];
        Arrays.fill(k, (byte) seed);
        return k;
    }

    private static Optional<UdpPacket> open(UdpPeer peer, byte[] wire) {
        return peer.open(ByteBuffer.wrap(wire));
    }

    @Test
    void constants() {
        assertThat(UdpPeer.REPLAY_WINDOW).isEqualTo(1024);
        assertThat(UdpPeer.PROBE_FAST).hasMillis(200);
        assertThat(UdpPeer.PROBE_SLOW).hasSeconds(1);
        assertThat(UdpPeer.PROBE_LENGTH).isEqualTo(8);
        assertThat(UdpPeer.REPLY_LENGTH).isEqualTo(8);
    }

    @Test
    void lastSealedNumberFollowsEverySeal() {
        assertThat(agent.lastSealedNumber()).isZero();
        byte[] tile = agent.seal(Type.TILE, new byte[3]);
        assertThat(agent.lastSealedNumber()).isEqualTo(open(viewer, tile).orElseThrow().number());
        byte[] probe = agent.probe(42);
        assertThat(agent.lastSealedNumber()).isEqualTo(open(viewer, probe).orElseThrow().number()).isEqualTo(2);
        UdpPacket p = open(agent, viewer.probe(7)).orElseThrow();
        agent.answer(p).orElseThrow();
        assertThat(agent.lastSealedNumber()).isEqualTo(3);
    }

    @Test
    void opensWhatTheOtherSideSealed() {
        UdpPacket p = open(agent, viewer.seal(Type.PROBE, ProbePayload.encode(5, 8))).orElseThrow();

        assertThat(p.type()).isEqualTo(Type.PROBE);
        assertThat(p.from()).isEqualTo(Direction.VIEWER);
        assertThat(p.sessionId()).isEqualTo(SESSION);
        assertThat(ProbePayload.sentNanos(p.payload())).hasValue(5);
    }

    @Test
    void ownDirectionIsRejected() {
        // Same key both ways: only the signed direction stops a packet reflected back to its sender.
        byte[] own = agent.seal(Type.PROBE, ProbePayload.encode(5, 8));

        assertThat(open(agent, own)).isEmpty();
    }

    @Test
    void replayIsRejected() {
        byte[] wire = viewer.seal(Type.PROBE, ProbePayload.encode(5, 8));

        assertThat(open(agent, wire)).isPresent();
        assertThat(open(agent, wire)).isEmpty();
    }

    @Test
    void wrongKeyDoesNotMoveTheWindow() {
        UdpPeer forger = new UdpPeer(Direction.VIEWER, SESSION, key(2));
        byte[] forged = forger.seal(Type.PROBE, ProbePayload.encode(5, 8));
        byte[] genuine = viewer.seal(Type.PROBE, ProbePayload.encode(6, 8)); // same number 1

        assertThat(open(agent, forged)).isEmpty();
        assertThat(open(agent, genuine)).isPresent();
    }

    @Test
    void otherSessionAndGarbageAreRejected() {
        UdpPeer otherSession = new UdpPeer(Direction.VIEWER, SESSION + 1, key);

        assertThat(open(agent, otherSession.seal(Type.PROBE, ProbePayload.encode(5, 8)))).isEmpty();
        assertThat(open(agent, new byte[0])).isEmpty();
        assertThat(open(agent, new byte[100])).isEmpty();
    }

    @Test
    void sealNumbersFromOne() {
        long first = open(agent, viewer.seal(Type.PROBE, ProbePayload.encode(1, 8))).orElseThrow().number();
        long second = open(agent, viewer.probe(2)).orElseThrow().number();
        UdpPacket probe = open(viewer, agent.probe(3)).orElseThrow();
        long third = open(agent, viewer.answer(probe).orElseThrow()).orElseThrow().number();

        assertThat(first).isEqualTo(1);
        assertThat(second).isEqualTo(2);
        assertThat(third).isEqualTo(3); // probes, replies and sealed packets share one counter
    }

    @Test
    void answerEchoesSentNanosInAShortReply() {
        UdpPacket probe = open(agent, viewer.seal(Type.PROBE, ProbePayload.encode(77, 64))).orElseThrow();

        UdpPacket reply = open(viewer, agent.answer(probe).orElseThrow()).orElseThrow();

        assertThat(reply.type()).isEqualTo(Type.PROBE_REPLY);
        assertThat(reply.from()).isEqualTo(Direction.AGENT);
        assertThat(reply.payload()).hasSize(UdpPeer.REPLY_LENGTH);
        assertThat(ProbePayload.sentNanos(reply.payload())).hasValue(77);
    }

    @Test
    void answerOnlyAnswersProbes() {
        UdpPacket reply = open(agent, viewer.seal(Type.PROBE_REPLY, ProbePayload.encode(77, 8))).orElseThrow();
        UdpPacket shortProbe = open(agent, viewer.seal(Type.PROBE, new byte[4])).orElseThrow();

        assertThat(agent.answer(reply)).isEmpty();
        assertThat(agent.answer(shortProbe)).isEmpty();
    }

    @Test
    void rttOfOwnProbe() {
        UdpPacket probe = open(viewer, agent.probe(1_000)).orElseThrow();
        UdpPacket reply = open(agent, viewer.answer(probe).orElseThrow()).orElseThrow();

        assertThat(agent.rttOf(reply, 1_250)).hasValue(250);
    }

    @Test
    void rttOfAnyProbeInTheSentRange() {
        open(viewer, agent.probe(1_000)).orElseThrow();
        UdpPacket second = open(viewer, agent.probe(2_000)).orElseThrow();
        UdpPacket reply = open(agent, viewer.answer(second).orElseThrow()).orElseThrow();

        assertThat(agent.rttOf(reply, 2_100)).hasValue(100);
    }

    @Test
    void rttOfForeignEchoIsEmpty() {
        agent.probe(1_000);
        agent.probe(2_000);
        UdpPacket before = open(agent, viewer.seal(Type.PROBE_REPLY, ProbePayload.encode(999, 8))).orElseThrow();
        UdpPacket after = open(agent, viewer.seal(Type.PROBE_REPLY, ProbePayload.encode(2_001, 8))).orElseThrow();

        assertThat(agent.rttOf(before, 3_000)).isEmpty();
        assertThat(agent.rttOf(after, 3_000)).isEmpty();
    }

    @Test
    void rttBeforeAnyProbeIsEmpty() {
        UdpPacket reply = open(agent, viewer.seal(Type.PROBE_REPLY, ProbePayload.encode(0, 8))).orElseThrow();

        assertThat(agent.rttOf(reply, 10)).isEmpty();
    }

    @Test
    void rttOfAProbeIsEmpty() {
        agent.probe(1_000);
        UdpPacket probe = open(agent, viewer.seal(Type.PROBE, ProbePayload.encode(1_000, 8))).orElseThrow();

        assertThat(agent.rttOf(probe, 1_500)).isEmpty();
    }
}

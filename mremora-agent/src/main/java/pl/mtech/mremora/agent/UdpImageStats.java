package pl.mtech.mremora.agent;

/**
 * Counters of the picture sent over UDP, cumulative since the server started (all sessions); diagnostics and tests
 * (see {@link AgentServer#udpImageStats()}).
 *
 * @param packetsSent         {@code TILE} packets sent; lost pixels are re-encoded into new packets, which count again
 * @param packetsLost         sent packets the loss detector declared lost
 * @param rectsSent           rectangles handed to the UDP sender
 * @param fragmentsSent       fragments those rectangles were split into
 * @param maxFragmentsPerRect most fragments of any one rectangle
 * @param tilesForced         tiles forced dirty again because a rectangle carrying their latest pixels was lost
 * @param rateBitsPerSecond   current send rate of the pacer of the latest session, {@code 0} before the first
 * @param injectorDrops       datagrams the developer/test {@link pl.mtech.mremora.protocol.netty.LinkImpairment}
 *                            dropped on the agent's socket, both directions; {@code 0} without one
 * @param datagramsSent       every UDP datagram the agent sent: {@code TILE}s, the sender's probes, the link's
 *                            probes (NAT keepalive) and probe replies; counted before the impairment
 */
public record UdpImageStats(long packetsSent, long packetsLost, long rectsSent, long fragmentsSent,
                            int maxFragmentsPerRect, long tilesForced, long rateBitsPerSecond, long injectorDrops,
                            long datagramsSent) {

    /** Nothing sent yet. */
    public static final UdpImageStats ZERO = new UdpImageStats(0, 0, 0, 0, 0, 0, 0, 0, 0);

    /** A copy with the counters read live from the socket replaced. */
    public UdpImageStats withSocketCounters(long drops, long datagrams) {
        return new UdpImageStats(packetsSent, packetsLost, rectsSent, fragmentsSent, maxFragmentsPerRect,
                tilesForced, rateBitsPerSecond, drops, datagrams);
    }
}

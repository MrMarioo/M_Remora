package pl.mtech.mremora.protocol.udp;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import pl.mtech.mremora.protocol.udp.RangeSet.Range;

/**
 * Payload of an {@code ACK} packet (big-endian):
 * {@code u16 ackDelayMs | u8 n | n x (u64 from, u64 to) | u8 m | m x (u32 from, u32 to) | u8 k | k x u32}.
 * Packet-number ranges, then finished rectangle ids (applied or skipped) ranges, then dropped rectangle ids.
 * Ranges are closed, {@code from <= to}, strictly disjoint and newest first; each list has at most 32 entries
 * (901 bytes at most). The constructor enforces the rules, so {@link #decode} returns empty for a violation.
 *
 * @param packets    acknowledged packet numbers
 * @param doneRects  rect ids the viewer applied or skipped (u32 values)
 * @param droppedRects rect ids the viewer discarded (u32 values)
 */
public record AckFrame(int ackDelayMs, List<Range> packets, List<Range> doneRects, List<Long> droppedRects) {

    public static final int MAX_RANGES = 32;
    public static final int MAX_DROPPED = 32;

    private static final long U32 = 0xFFFFFFFFL;

    public AckFrame {
        if (ackDelayMs < 0 || ackDelayMs > 0xFFFF) {
            throw new IllegalArgumentException("ackDelayMs out of u16: " + ackDelayMs);
        }
        packets = checked(packets, Long.MAX_VALUE, "packets");
        doneRects = checked(doneRects, U32, "doneRects");
        droppedRects = List.copyOf(droppedRects);
        if (droppedRects.size() > MAX_DROPPED) {
            throw new IllegalArgumentException("too many dropped rects: " + droppedRects.size());
        }
        for (long id : droppedRects) {
            if (id < 0 || id > U32) {
                throw new IllegalArgumentException("dropped rect id out of u32: " + id);
            }
        }
    }

    private static List<Range> checked(List<Range> ranges, long limit, String what) {
        List<Range> copy = List.copyOf(ranges);
        if (copy.size() > MAX_RANGES) {
            throw new IllegalArgumentException("too many " + what + " ranges: " + copy.size());
        }
        Range previous = null;
        for (Range r : copy) {
            if (r.to() > limit || (previous != null && r.to() >= previous.from())) {
                throw new IllegalArgumentException(what + " ranges must be disjoint, newest first, in range");
            }
            previous = r;
        }
        return copy;
    }

    public byte[] encode() {
        ByteBuffer out = ByteBuffer.allocate(2 + 1 + packets.size() * 16 + 1 + doneRects.size() * 8
                + 1 + droppedRects.size() * 4);
        out.putShort((short) ackDelayMs).put((byte) packets.size());
        for (Range r : packets) {
            out.putLong(r.from()).putLong(r.to());
        }
        out.put((byte) doneRects.size());
        for (Range r : doneRects) {
            out.putInt((int) r.from()).putInt((int) r.to());
        }
        out.put((byte) droppedRects.size());
        for (long id : droppedRects) {
            out.putInt((int) id);
        }
        return out.array();
    }

    /** Never throws for malformed input; empty for anything that is not a valid frame (also trailing bytes). */
    public static Optional<AckFrame> decode(byte[] payload) {
        ByteBuffer in = ByteBuffer.wrap(payload);
        try {
            int delay = in.getShort() & 0xFFFF;
            int n = in.get() & 0xFF;
            if (n > MAX_RANGES) {
                return Optional.empty();
            }
            List<Range> packets = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                packets.add(new Range(in.getLong(), in.getLong()));
            }
            int m = in.get() & 0xFF;
            if (m > MAX_RANGES) {
                return Optional.empty();
            }
            List<Range> done = new ArrayList<>(m);
            for (int i = 0; i < m; i++) {
                done.add(new Range(in.getInt() & U32, in.getInt() & U32));
            }
            int k = in.get() & 0xFF;
            if (k > MAX_DROPPED) {
                return Optional.empty();
            }
            List<Long> dropped = new ArrayList<>(k);
            for (int i = 0; i < k; i++) {
                dropped.add(in.getInt() & U32);
            }
            if (in.hasRemaining()) {
                return Optional.empty();
            }
            return Optional.of(new AckFrame(delay, packets, done, dropped));
        } catch (java.nio.BufferUnderflowException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}

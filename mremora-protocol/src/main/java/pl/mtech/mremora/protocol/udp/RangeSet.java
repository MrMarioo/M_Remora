package pl.mtech.mremora.protocol.udp;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * A set of numbers {@code >= 0} kept as disjoint, non-adjacent closed ranges ({@code from -> to}); neighbouring
 * ranges are merged. Memory is bounded by the number of gaps, not by the numbers. Not thread-safe.
 */
public final class RangeSet {

    /** Closed range {@code [from, to]}, {@code 0 <= from <= to}. */
    public record Range(long from, long to) {
        public Range {
            if (from < 0 || from > to) {
                throw new IllegalArgumentException("invalid range [%d, %d]".formatted(from, to));
            }
        }
    }

    private final TreeMap<Long, Long> ranges = new TreeMap<>();

    /** Adds {@code n}; merges with the neighbouring ranges when adjacent. */
    public void add(long n) {
        if (n < 0) {
            throw new IllegalArgumentException("n must be >= 0: " + n);
        }
        Map.Entry<Long, Long> lower = ranges.floorEntry(n);
        if (lower != null && lower.getValue() >= n) {
            return;
        }
        long from = n;
        long to = n;
        if (lower != null && lower.getValue() == n - 1) {
            from = lower.getKey();
        }
        Long upperTo = ranges.get(n + 1);
        if (upperTo != null && n != Long.MAX_VALUE) {
            to = upperTo;
            ranges.remove(n + 1);
        }
        ranges.put(from, to);
    }

    public boolean contains(long n) {
        Map.Entry<Long, Long> e = ranges.floorEntry(n);
        return e != null && e.getValue() >= n;
    }

    /** Up to {@code max} ranges, newest (highest) first. */
    public List<Range> newest(int max) {
        List<Range> out = new ArrayList<>(Math.min(Math.max(max, 0), ranges.size()));
        for (Map.Entry<Long, Long> e : ranges.descendingMap().entrySet()) {
            if (out.size() >= max) {
                break;
            }
            out.add(new Range(e.getKey(), e.getValue()));
        }
        return out;
    }

    /** Forgets every number {@code < n}; a range spanning {@code n} is cut. */
    public void removeBelow(long n) {
        Map.Entry<Long, Long> first;
        while ((first = ranges.firstEntry()) != null && first.getKey() < n) {
            ranges.remove(first.getKey());
            if (first.getValue() >= n) {
                ranges.put(n, first.getValue());
            }
        }
    }

    public boolean isEmpty() {
        return ranges.isEmpty();
    }

    /** The largest number in the set; throws {@link java.util.NoSuchElementException} when empty. */
    public long max() {
        return ranges.lastEntry().getValue();
    }
}

package pl.mtech.mremora.protocol.udp;

import java.util.BitSet;

/**
 * Accepts every message number at most once, remembering only the last {@code size} numbers: a number
 * {@code <= max - size} (where {@code max} is the highest accepted so far) is too old and never fresh.
 * Before the first {@link #accept} every number {@code >= 0} is fresh. Single-threaded.
 *
 * <p>Ring of {@code size} bits: number {@code n} lives at slot {@code n % size}. The window always covers
 * {@code size} consecutive numbers, so no two of them share a slot; moving the window forward clears the slots
 * of the numbers that fell out.
 */
public final class ReplayWindow {

    public static final int MAX_SIZE = 65_536;

    private final int size;
    private final BitSet seen;
    /** Highest accepted number, {@code -1} before the first accept. */
    private long max = -1;

    /** @param size window size, {@code 1..65536} */
    public ReplayWindow(int size) {
        if (size < 1 || size > MAX_SIZE) {
            throw new IllegalArgumentException("size must be 1.." + MAX_SIZE + ", was " + size);
        }
        this.size = size;
        this.seen = new BitSet(size);
    }

    /** Whether {@code number} may be accepted; changes nothing. */
    public boolean isFresh(long number) {
        if (number < 0) {
            return false;
        }
        if (max < 0 || number > max) {
            return true;
        }
        if (number <= max - size) {
            return false;
        }
        return !seen.get(slot(number));
    }

    /**
     * Records {@code number} as accepted.
     *
     * @throws IllegalArgumentException when {@code number} is not fresh
     */
    public void accept(long number) {
        if (!isFresh(number)) {
            throw new IllegalArgumentException("not fresh: " + number);
        }
        if (number > max) {
            advanceTo(number);
        }
        seen.set(slot(number));
    }

    /** Moves the window so that {@code newMax} is its top: slots of numbers that fell out are cleared. */
    private void advanceTo(long newMax) {
        if (max < 0 || newMax - max >= size) {
            seen.clear(); // jumped past the whole window: nothing old is still inside
        } else {
            for (long n = max + 1; n <= newMax; n++) {
                seen.clear(slot(n));
            }
        }
        max = newMax;
    }

    private int slot(long number) {
        return (int) (number % size);
    }
}

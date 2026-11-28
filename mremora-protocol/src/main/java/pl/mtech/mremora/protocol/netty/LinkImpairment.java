package pl.mtech.mremora.protocol.netty;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.time.Duration;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Deterministic (seeded) description of an unreliable link, for tests and as a developer option
 * ({@value #DEV_PROPERTY}, see {@link #parse}).
 *
 * @param bandwidthBitsPerSecond rate limit of each direction, {@code 0} for none; packets beyond a 64 KiB router
 *     queue are dropped (see {@link ImpairedDatagramHandler})
 */
public record LinkImpairment(
        double lossRate,
        double duplicateRate,
        Duration delay,
        Duration jitter,
        boolean blockInbound,
        boolean blockOutbound,
        long seed,
        long bandwidthBitsPerSecond) {

    /** System property with a {@link #parse} spec that impairs the UDP channel of the agent or viewer (developers). */
    public static final String DEV_PROPERTY = "mremora.dev.udpImpairment";

    /**
     * Upper bound of {@code delay} and of {@code jitter}, so per-packet nanosecond arithmetic cannot overflow.
     * Declared before {@link #NONE} (static initialisation order).
     */
    public static final Duration MAX_DELAY = Duration.ofHours(1);

    public static final LinkImpairment NONE =
            new LinkImpairment(0, 0, Duration.ZERO, Duration.ZERO, false, false, 0, 0);

    private static final Logger LOG = System.getLogger(LinkImpairment.class.getName());

    public LinkImpairment {
        Objects.requireNonNull(delay, "delay");
        Objects.requireNonNull(jitter, "jitter");
        if (!(lossRate >= 0 && lossRate <= 1)) {
            throw new IllegalArgumentException("lossRate must be in 0..1: " + lossRate);
        }
        if (!(duplicateRate >= 0 && duplicateRate <= 1)) {
            throw new IllegalArgumentException("duplicateRate must be in 0..1: " + duplicateRate);
        }
        if (delay.isNegative() || jitter.isNegative()) {
            throw new IllegalArgumentException("delay and jitter must be >= 0");
        }
        if (delay.compareTo(MAX_DELAY) > 0 || jitter.compareTo(MAX_DELAY) > 0) {
            throw new IllegalArgumentException("delay and jitter must be <= 1 h, were " + delay + " and " + jitter);
        }
        if (bandwidthBitsPerSecond < 0) {
            throw new IllegalArgumentException("bandwidthBitsPerSecond must be >= 0: " + bandwidthBitsPerSecond);
        }
    }

    /** Without a bandwidth limit. */
    public LinkImpairment(double lossRate, double duplicateRate, Duration delay, Duration jitter,
                          boolean blockInbound, boolean blockOutbound, long seed) {
        this(lossRate, duplicateRate, delay, jitter, blockInbound, blockOutbound, seed, 0);
    }

    /** Both directions blocked. */
    public static LinkImpairment blocked() {
        return new LinkImpairment(0, 0, Duration.ZERO, Duration.ZERO, true, true, 0);
    }

    /**
     * Parses a spec such as {@code loss=0.03,dup=0.01,delay=50ms,jitter=10ms,bandwidth=2m,seed=7,block=in}.
     * Comma-separated {@code key=value} entries, each key at most once, in any order, whitespace around entries,
     * keys and values ignored; unset keys mean no impairment of that kind.
     * <ul>
     *   <li>{@code loss}, {@code dup}: probability {@code 0..1}</li>
     *   <li>{@code delay}, {@code jitter}: non-negative whole number with unit {@code ms} or {@code s}
     *       (lower case, unit required), at most {@link #MAX_DELAY}</li>
     *   <li>{@code bandwidth}: bits per second, whole number, optional suffix {@code k} (×10³) or {@code m} (×10⁶),
     *       either case; {@code 0} = no limit</li>
     *   <li>{@code seed}: {@code long}</li>
     *   <li>{@code block}: {@code in}, {@code out} or {@code both}</li>
     * </ul>
     *
     * @throws IllegalArgumentException for an empty spec, an empty entry, an unknown or repeated key, or a bad value
     */
    public static LinkImpairment parse(String spec) {
        Objects.requireNonNull(spec, "spec");
        if (spec.isBlank()) {
            throw new IllegalArgumentException("empty link impairment spec");
        }
        double loss = 0;
        double dup = 0;
        Duration delay = Duration.ZERO;
        Duration jitter = Duration.ZERO;
        boolean blockIn = false;
        boolean blockOut = false;
        long seed = 0;
        long bandwidth = 0;
        Set<String> seen = new HashSet<>();
        for (String entry : spec.split(",", -1)) {
            int eq = entry.indexOf('=');
            if (eq < 0) {
                throw new IllegalArgumentException("expected key=value, got '" + entry.strip() + "' in: " + spec);
            }
            String key = entry.substring(0, eq).strip();
            String value = entry.substring(eq + 1).strip();
            if (!seen.add(key)) {
                throw new IllegalArgumentException("repeated key '" + key + "' in: " + spec);
            }
            try {
                switch (key) {
                    case "loss" -> loss = probability(value);
                    case "dup" -> dup = probability(value);
                    case "delay" -> delay = duration(value);
                    case "jitter" -> jitter = duration(value);
                    case "bandwidth" -> bandwidth = bandwidth(value);
                    case "seed" -> seed = Long.parseLong(value);
                    case "block" -> {
                        switch (value) {
                            case "in" -> blockIn = true;
                            case "out" -> blockOut = true;
                            case "both" -> {
                                blockIn = true;
                                blockOut = true;
                            }
                            default -> throw new IllegalArgumentException("block must be in, out or both");
                        }
                    }
                    default -> throw new IllegalArgumentException("unknown key");
                }
            } catch (IllegalArgumentException | ArithmeticException e) { // NumberFormatException is an IAE
                throw new IllegalArgumentException(
                        "bad entry '" + entry.strip() + "' in link impairment spec '" + spec + "': " + e.getMessage(),
                        e);
            }
        }
        return new LinkImpairment(loss, dup, delay, jitter, blockIn, blockOut, seed, bandwidth);
    }

    /**
     * The impairment set by a {@value #DEV_PROPERTY} value: {@link #NONE} when {@code null} (property absent),
     * otherwise {@link #parse} (so a blank value is an error).
     *
     * @throws IllegalArgumentException naming the property, for a value {@link #parse} rejects
     */
    public static LinkImpairment fromProperty(String value) {
        if (value == null) {
            return NONE;
        }
        try {
            return parse(value);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(DEV_PROPERTY + ": " + e.getMessage(), e);
        }
    }

    /**
     * {@link #fromProperty} of the system property {@value #DEV_PROPERTY}; logs a {@code WARNING} when it is set.
     *
     * @throws IllegalArgumentException for a malformed value
     */
    public static LinkImpairment fromSystemProperty() {
        LinkImpairment impairment = fromProperty(System.getProperty(DEV_PROPERTY));
        if (impairment != NONE) {
            LOG.log(Level.WARNING, "UDP impairment active: {0} ({1})", impairment, DEV_PROPERTY);
        }
        return impairment;
    }

    private static double probability(String value) {
        return Double.parseDouble(value); // range checked by the constructor, NaN included
    }

    private static Duration duration(String value) {
        if (value.endsWith("ms")) {
            return Duration.ofMillis(nonNegative(value.substring(0, value.length() - 2)));
        }
        if (value.endsWith("s")) {
            return Duration.ofSeconds(nonNegative(value.substring(0, value.length() - 1)));
        }
        throw new IllegalArgumentException("duration needs unit ms or s");
    }

    private static long bandwidth(String value) {
        long factor = 1;
        String digits = value;
        if (!value.isEmpty()) {
            char last = value.charAt(value.length() - 1);
            if (last == 'k' || last == 'K') {
                factor = 1_000;
            } else if (last == 'm' || last == 'M') {
                factor = 1_000_000;
            }
            if (factor != 1) {
                digits = value.substring(0, value.length() - 1);
            }
        }
        return Math.multiplyExact(nonNegative(digits), factor);
    }

    private static long nonNegative(String digits) {
        if (digits.isEmpty() || !digits.chars().allMatch(c -> c >= '0' && c <= '9')) {
            throw new IllegalArgumentException("expected a non-negative whole number, got '" + digits + "'");
        }
        return Long.parseLong(digits);
    }
}

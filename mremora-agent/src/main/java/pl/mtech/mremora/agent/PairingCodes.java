package pl.mtech.mremora.agent;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.random.RandomGenerator;

/**
 * One-time pairing code of the agent: validity, single use, retry delay and lockout.
 *
 * <p>Not thread-safe; meant to be used only from the server's event-loop thread.
 */
public final class PairingCodes {

    public static final Duration VALIDITY = Duration.ofMinutes(10);
    public static final Duration RETRY_DELAY = Duration.ofSeconds(1);
    public static final int MAX_FAILURES = 5;
    public static final Duration LOCKOUT = Duration.ofSeconds(60);

    /** Current state of the pairing code. */
    public sealed interface Status permits Available, Locked {}

    /** A code that can be shown to the user. */
    public record Available(String code) implements Status {}

    /** Pairing is locked after too many failures; {@code remaining} is the time until a new code is issued. */
    public record Locked(Duration remaining) implements Status {}

    /** Outcome of {@link #verify(String)}. */
    public enum Result { ACCEPTED, REJECTED, TOO_SOON, LOCKED }

    private final Clock clock;
    private final RandomGenerator random;

    private String code;
    private Instant issuedAt;
    private Instant lastFailure;
    private Instant lockedUntil;
    private int failures;

    public PairingCodes(Clock clock, RandomGenerator random) {
        this.clock = clock;
        this.random = random;
        issueNewCode(clock.instant());
    }

    public Status status() {
        Instant now = clock.instant();
        refresh(now);
        return lockedUntil != null ? new Locked(Duration.between(now, lockedUntil)) : new Available(code);
    }

    public Result verify(String candidate) {
        Instant now = clock.instant();
        refresh(now);
        if (lockedUntil != null) {
            return Result.LOCKED;
        }
        if (lastFailure != null && Duration.between(lastFailure, now).compareTo(RETRY_DELAY) < 0) {
            return Result.TOO_SOON;
        }
        if (candidate != null && MessageDigest.isEqual(
                candidate.getBytes(StandardCharsets.UTF_8), code.getBytes(StandardCharsets.UTF_8))) {
            issueNewCode(now);
            return Result.ACCEPTED;
        }
        lastFailure = now;
        if (++failures >= MAX_FAILURES) {
            lockedUntil = now.plus(LOCKOUT);
        }
        return Result.REJECTED;
    }

    private void refresh(Instant now) {
        if (lockedUntil != null) {
            if (now.isBefore(lockedUntil)) {
                return;
            }
            issueNewCode(now);
        } else if (Duration.between(issuedAt, now).compareTo(VALIDITY) >= 0) {
            issueNewCode(now);
        }
    }

    private void issueNewCode(Instant now) {
        code = "%06d".formatted(random.nextInt(1_000_000));
        issuedAt = now;
        lastFailure = null;
        lockedUntil = null;
        failures = 0;
    }
}

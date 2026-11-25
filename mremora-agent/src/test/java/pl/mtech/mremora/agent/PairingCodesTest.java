package pl.mtech.mremora.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.Random;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import pl.mtech.mremora.agent.PairingCodes.Available;
import pl.mtech.mremora.agent.PairingCodes.Locked;
import pl.mtech.mremora.agent.PairingCodes.Result;
import pl.mtech.mremora.testkit.MutableClock;

class PairingCodesTest {

    private MutableClock clock;
    private PairingCodes codes;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        codes = new PairingCodes(clock, new Random(42));
    }

    private String currentCode() {
        return ((Available) codes.status()).code();
    }

    private String wrongCode() {
        return currentCode().equals("000000") ? "000001" : "000000";
    }

    @Test
    void codeHasSixDigits() {
        assertThat(codes.status()).isInstanceOfSatisfying(Available.class,
                a -> assertThat(a.code()).matches("\\d{6}"));
    }

    @Test
    void correctCodeIsAcceptedOnce() {
        String code = currentCode();

        assertThat(codes.verify(code)).isEqualTo(Result.ACCEPTED);
        clock.advance(Duration.ofSeconds(2));
        assertThat(codes.verify(code)).isEqualTo(Result.REJECTED);
        assertThat(currentCode()).isNotEqualTo(code);
    }

    @Test
    void codeExpiresAfterTenMinutes() {
        String old = currentCode();

        clock.advance(Duration.ofMinutes(10));

        assertThat(codes.verify(old)).isEqualTo(Result.REJECTED);
        assertThat(currentCode()).isNotEqualTo(old);
    }

    @Test
    void retryWithinOneSecondIsTooSoon() {
        String wrong = wrongCode();

        assertThat(codes.verify(wrong)).isEqualTo(Result.REJECTED);
        clock.advance(Duration.ofMillis(500));
        assertThat(codes.verify(wrong)).isEqualTo(Result.TOO_SOON);
        clock.advance(Duration.ofMillis(600));
        assertThat(codes.verify(wrong)).isEqualTo(Result.REJECTED);
    }

    @Test
    void fiveFailuresLock() {
        String before = currentCode();
        String wrong = wrongCode();

        for (int i = 0; i < 5; i++) {
            assertThat(codes.verify(wrong)).isEqualTo(Result.REJECTED);
            clock.advance(Duration.ofSeconds(1));
        }

        assertThat(codes.status()).isInstanceOfSatisfying(Locked.class,
                l -> assertThat(l.remaining()).isBetween(Duration.ofSeconds(58), Duration.ofSeconds(60)));
        assertThat(codes.verify(before)).isEqualTo(Result.LOCKED);
    }

    @Test
    void lockoutEndsWithNewCodeAfterSixtySeconds() {
        String before = currentCode();
        String wrong = wrongCode();
        for (int i = 0; i < 5; i++) {
            codes.verify(wrong);
            clock.advance(Duration.ofSeconds(1));
        }

        clock.advance(Duration.ofSeconds(60));

        assertThat(codes.status()).isInstanceOf(Available.class);
        String fresh = currentCode();
        assertThat(fresh).isNotEqualTo(before);
        assertThat(codes.verify(fresh)).isEqualTo(Result.ACCEPTED);
    }

    @Test
    void tooSoonDoesNotCountTowardsLockout() {
        String wrong = wrongCode();
        for (int i = 0; i < 4; i++) {
            assertThat(codes.verify(wrong)).isEqualTo(Result.REJECTED);
            if (i < 3) {
                clock.advance(Duration.ofSeconds(1));
            }
        }

        for (int i = 0; i < 10; i++) {
            assertThat(codes.verify(wrong)).isEqualTo(Result.TOO_SOON);
        }

        assertThat(codes.status()).isInstanceOf(Available.class);
    }
}

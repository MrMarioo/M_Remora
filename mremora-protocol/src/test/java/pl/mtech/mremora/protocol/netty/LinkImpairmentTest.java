package pl.mtech.mremora.protocol.netty;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class LinkImpairmentTest {

    @Test
    void parseFull() {
        LinkImpairment i = LinkImpairment.parse("loss=0.03,dup=0.01,delay=50ms,jitter=10ms,bandwidth=2m,seed=7,block=in");

        assertThat(i).isEqualTo(new LinkImpairment(0.03, 0.01, Duration.ofMillis(50), Duration.ofMillis(10),
                true, false, 7, 2_000_000));
    }

    @Test
    void parseDefaultsUnsetKeysAndTrimsWhitespace() {
        LinkImpairment i = LinkImpairment.parse(" loss = 0.5 , delay=2s ");

        assertThat(i).isEqualTo(new LinkImpairment(0.5, 0, Duration.ofSeconds(2), Duration.ZERO,
                false, false, 0, 0));
    }

    @Test
    void parseBandwidthUnits() {
        assertThat(LinkImpairment.parse("bandwidth=500k").bandwidthBitsPerSecond()).isEqualTo(500_000);
        assertThat(LinkImpairment.parse("bandwidth=3M").bandwidthBitsPerSecond()).isEqualTo(3_000_000);
        assertThat(LinkImpairment.parse("bandwidth=1200").bandwidthBitsPerSecond()).isEqualTo(1200);
        assertThat(LinkImpairment.parse("bandwidth=0").bandwidthBitsPerSecond()).isZero();
    }

    @Test
    void parseBlock() {
        assertThat(LinkImpairment.parse("block=in").blockInbound()).isTrue();
        assertThat(LinkImpairment.parse("block=in").blockOutbound()).isFalse();
        assertThat(LinkImpairment.parse("block=out").blockInbound()).isFalse();
        assertThat(LinkImpairment.parse("block=out").blockOutbound()).isTrue();
        assertThat(LinkImpairment.parse("block=both")).isEqualTo(LinkImpairment.blocked());
    }

    @Test
    void parseRejectsUnknownKey() {
        assertThatThrownBy(() -> LinkImpairment.parse("loss=0.1,latency=5ms"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("latency");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "loss=0.1,", "loss", "loss=", "=0.1", "loss=0.1,loss=0.2", "loss=1.5",
            "loss=abc", "dup=-0.1", "delay=50", "delay=50us", "delay=-5ms", "delay=1.5ms", "jitter=10MS",
            "bandwidth=2g", "bandwidth=-1", "bandwidth=m", "bandwidth=99999999999999m", "seed=x", "block=up",
            "block=IN"})
    void parseRejectsMalformed(String spec) {
        assertThatThrownBy(() -> LinkImpairment.parse(spec)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void noneWhenPropertyAbsent() {
        assertThat(LinkImpairment.fromProperty(null)).isSameAs(LinkImpairment.NONE);
        assertThat(LinkImpairment.fromProperty("loss=0.2")).isEqualTo(LinkImpairment.parse("loss=0.2"));
        assertThatThrownBy(() -> LinkImpairment.fromProperty("bogus=1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(LinkImpairment.DEV_PROPERTY);
    }

    @Test
    void sevenArgumentConstructorHasNoBandwidthLimit() {
        LinkImpairment i = new LinkImpairment(0.1, 0, Duration.ZERO, Duration.ZERO, false, false, 3);

        assertThat(i.bandwidthBitsPerSecond()).isZero();
        assertThat(LinkImpairment.NONE.bandwidthBitsPerSecond()).isZero();
    }

    @Test
    void rejectsDelayOrJitterAboveOneHour() {
        assertThatThrownBy(() -> LinkImpairment.parse("delay=9999999999s"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("1 h");
        assertThatThrownBy(() -> LinkImpairment.parse("jitter=3600001ms"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(LinkImpairment.parse("delay=3600s,jitter=3600000ms").delay()).isEqualTo(Duration.ofHours(1));
        assertThatThrownBy(() -> new LinkImpairment(0, 0, Duration.ofSeconds(Long.MAX_VALUE), Duration.ZERO,
                false, false, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNegativeBandwidth() {
        assertThatThrownBy(() -> new LinkImpairment(0, 0, Duration.ZERO, Duration.ZERO, false, false, 0, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

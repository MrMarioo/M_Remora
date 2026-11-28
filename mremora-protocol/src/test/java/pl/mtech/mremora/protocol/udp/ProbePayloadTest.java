package pl.mtech.mremora.protocol.udp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

class ProbePayloadTest {

    @Test
    void roundTrip() {
        byte[] payload = ProbePayload.encode(0x0102030405060708L, 8);

        assertThat(payload).containsExactly(1, 2, 3, 4, 5, 6, 7, 8);
        assertThat(ProbePayload.sentNanos(payload)).hasValue(0x0102030405060708L);
    }

    @Test
    void negativeNanosSurvive() {
        assertThat(ProbePayload.sentNanos(ProbePayload.encode(-42L, 8))).hasValue(-42L);
    }

    @Test
    void paddingIsZeros() {
        byte[] payload = ProbePayload.encode(-1L, UdpPacketCodec.MAX_PAYLOAD);

        assertThat(payload).hasSize(UdpPacketCodec.MAX_PAYLOAD);
        for (int i = 8; i < payload.length; i++) {
            assertThat(payload[i]).isZero();
        }
        assertThat(ProbePayload.sentNanos(payload)).hasValue(-1L);
    }

    @Test
    void tooShortIsEmpty() {
        assertThat(ProbePayload.sentNanos(new byte[7])).isEqualTo(OptionalLong.empty());
        assertThat(ProbePayload.sentNanos(new byte[0])).isEqualTo(OptionalLong.empty());
    }

    @Test
    void lengthBounds() {
        assertThatThrownBy(() -> ProbePayload.encode(1, 7)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ProbePayload.encode(1, UdpPacketCodec.MAX_PAYLOAD + 1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

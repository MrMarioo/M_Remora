package pl.mtech.mremora.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DevProtocolVersionTest {

    @Test
    void defaultWhenPropertyAbsent() {
        assertThat(DevProtocolVersion.parse(null, 2, 3)).isEqualTo(2);
    }

    @Test
    void parsesVersionInRange() {
        assertThat(DevProtocolVersion.parse("3", 2, 3)).isEqualTo(3);
        assertThat(DevProtocolVersion.parse(" 1 ", 2, 3)).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "0", "4", "-1", "v3", "3.0", "99999999999"})
    void rejectsInvalidWithClearMessage(String value) {
        assertThatThrownBy(() -> DevProtocolVersion.parse(value, 2, 3))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(DevProtocolVersion.PROPERTY)
                .hasMessageContaining("1..3")
                .hasMessageContaining("'" + value + "'");
    }
}

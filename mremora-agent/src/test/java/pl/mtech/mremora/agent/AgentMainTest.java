package pl.mtech.mremora.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class AgentMainTest {

    @Test
    void noArgumentsUseDefaultPort() {
        assertThat(AgentMain.parsePort(new String[] {})).isEqualTo(7420);
    }

    @Test
    void portFlagSetsPort() {
        assertThat(AgentMain.parsePort(new String[] {"--port", "7500"})).isEqualTo(7500);
    }

    @Test
    void portBoundsAreAccepted() {
        assertThat(AgentMain.parsePort(new String[] {"--port", "1"})).isEqualTo(1);
        assertThat(AgentMain.parsePort(new String[] {"--port", "65535"})).isEqualTo(65535);
    }

    @Test
    void invalidArgumentsAreRejectedWithUsage() {
        String[][] bad = {
            {"--port", "0"}, {"--port", "65536"}, {"--port", "x"}, {"--port"}, {"-p", "1"},
            {"--port", "1", "extra"}
        };
        for (String[] args : bad) {
            assertThatThrownBy(() -> AgentMain.parsePort(args))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Usage");
        }
    }
}

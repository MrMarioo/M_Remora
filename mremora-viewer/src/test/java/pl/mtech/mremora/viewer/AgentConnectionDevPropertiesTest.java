package pl.mtech.mremora.viewer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import pl.mtech.mremora.protocol.DevProtocolVersion;
import pl.mtech.mremora.protocol.netty.LinkImpairment;

/** The developer properties of the default connect path; each test restores the properties it set. */
class AgentConnectionDevPropertiesTest {

    @AfterEach
    void clearProperties() {
        System.clearProperty(DevProtocolVersion.PROPERTY);
        System.clearProperty(LinkImpairment.DEV_PROPERTY);
    }

    @Test
    void defaultIsUnchangedWithoutProperties() {
        assertThat(AgentConnection.defaultProtocolVersion()).isEqualTo(AgentConnection.DEFAULT_PROTOCOL_VERSION);
        AgentConnection.checkDevProperties();
    }

    @Test
    void devProtocolVersionSelectsV3() {
        System.setProperty(DevProtocolVersion.PROPERTY, "3");

        assertThat(AgentConnection.defaultProtocolVersion()).isEqualTo(3);
    }

    @Test
    void startupCheckRejectsInvalidProtocolVersion() {
        System.setProperty(DevProtocolVersion.PROPERTY, "4");

        assertThatThrownBy(AgentConnection::checkDevProperties)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(DevProtocolVersion.PROPERTY);
    }

    @Test
    void startupCheckRejectsInvalidImpairment() {
        System.setProperty(LinkImpairment.DEV_PROPERTY, "loss=2");

        assertThatThrownBy(AgentConnection::checkDevProperties)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(LinkImpairment.DEV_PROPERTY);
    }
}

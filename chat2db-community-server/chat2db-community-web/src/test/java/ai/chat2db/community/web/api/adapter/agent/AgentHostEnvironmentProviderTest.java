package ai.chat2db.community.web.api.adapter.agent;

import ai.chat2db.community.domain.api.model.agent.runtime.AgentRuntimeEnvironmentRequest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class AgentHostEnvironmentProviderTest {

    @Test
    void readsEnvironmentFromTheHost() {
        AgentRuntimeEnvironmentRequest environment = new AgentHostEnvironmentProvider("5.3.0").current();

        assertEquals("5.3.0", environment.applicationVersion());
        assertFalse(environment.operatingSystem().isBlank());
        assertFalse(environment.architecture().isBlank());
    }
}

package ai.chat2db.community.web.api.controller;

import ai.chat2db.community.domain.api.model.agent.AgentRuntimeFeatureState;
import ai.chat2db.community.domain.api.model.agent.AgentRuntimeType;
import ai.chat2db.community.domain.api.model.agent.runtime.AgentRuntimeEnvironmentReport;
import ai.chat2db.community.domain.api.model.agent.runtime.AgentRuntimeEnvironmentRequest;
import ai.chat2db.community.domain.api.model.agent.runtime.AgentRuntimeEnvironmentStatus;
import ai.chat2db.community.domain.api.service.agent.AgentRuntimeFeatureService;
import ai.chat2db.community.web.api.adapter.agent.AgentHostEnvironmentProvider;
import ai.chat2db.community.web.api.model.request.agent.AgentRuntimeEnableRequest;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AgentFeatureControllerTest {

    @Test
    void routesPiFeatureOperationsWithoutClientEnvironmentInput() {
        RecordingService service = new RecordingService();
        AgentFeatureController controller = new AgentFeatureController(
                List.of(service), new AgentHostEnvironmentProvider("5.3.0"));

        assertEquals(1, controller.list().getData().size());
        assertEquals(true, controller.enablePi(new AgentRuntimeEnableRequest(true)).getData().enabled());
        assertEquals(false, controller.disablePi().getData().enabled());
        assertEquals("5.3.0", service.environment.applicationVersion());
    }

    private static final class RecordingService implements AgentRuntimeFeatureService {
        private AgentRuntimeEnvironmentRequest environment;
        @Override public AgentRuntimeType runtimeType() { return AgentRuntimeType.PI; }
        @Override public AgentRuntimeFeatureState check(AgentRuntimeEnvironmentRequest environment) {
            this.environment = environment;
            return state(false, environment);
        }
        @Override public AgentRuntimeFeatureState enable(AgentRuntimeEnvironmentRequest environment) {
            this.environment = environment;
            return state(true, environment);
        }
        @Override public AgentRuntimeFeatureState disable(AgentRuntimeEnvironmentRequest environment) {
            this.environment = environment;
            return state(false, environment);
        }
        private AgentRuntimeFeatureState state(boolean enabled, AgentRuntimeEnvironmentRequest environment) {
            return new AgentRuntimeFeatureState(
                    AgentRuntimeType.PI, enabled, enabled,
                    new AgentRuntimeEnvironmentReport(
                            AgentRuntimeType.PI, AgentRuntimeEnvironmentStatus.READY, "0.85.1",
                            environment.operatingSystem(), environment.architecture(), List.of(), Map.of(),
                            LocalDateTime.of(2026, 9, 9, 0, 0)));
        }
    }
}

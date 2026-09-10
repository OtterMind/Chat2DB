package ai.chat2db.community.web.api.controller;

import ai.chat2db.community.domain.api.model.agent.feature.AgentFeatureState;
import ai.chat2db.community.domain.api.model.agent.feature.AgentRuntimeFeatureState;
import ai.chat2db.community.domain.api.service.agent.AgentFeatureService;
import ai.chat2db.community.domain.api.service.agent.AgentRuntimeFeatureService;
import ai.chat2db.community.tools.enums.agent.AgentFeature;
import ai.chat2db.community.tools.enums.agent.AgentRuntimeEnvironmentStatus;
import ai.chat2db.community.tools.enums.agent.AgentRuntimeType;
import ai.chat2db.community.tools.model.agent.runtime.AgentRuntimeEnvironmentReport;
import ai.chat2db.community.tools.model.agent.runtime.AgentRuntimeEnvironmentRequest;
import ai.chat2db.community.web.api.adapter.agent.AgentHostEnvironmentProvider;
import ai.chat2db.community.web.api.model.request.agent.AgentRuntimeEnableRequest;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AgentFeatureControllerTest {

    @Test
    void routesPiFeatureOperationsWithoutClientEnvironmentInput() {
        RecordingService service = new RecordingService();
        RecordingBashService bashService = new RecordingBashService();
        AgentFeatureController controller = new AgentFeatureController(
                List.of(service), List.of(bashService), new AgentHostEnvironmentProvider("5.3.0"));

        assertEquals(1, controller.list().getData().size());
        assertEquals(true, controller.enablePi(new AgentRuntimeEnableRequest(true)).getData().enabled());
        assertEquals(false, controller.disablePi().getData().enabled());
        assertEquals(true, controller.enableBash(new AgentRuntimeEnableRequest(true)).getData().enabled());
        assertEquals(false, controller.disableBash().getData().enabled());
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

    private static final class RecordingBashService implements AgentFeatureService {
        private boolean enabled;
        @Override public AgentFeature feature() { return AgentFeature.BASH; }
        @Override public AgentFeatureState check() { return state(); }
        @Override public AgentFeatureState enable() { enabled = true; return state(); }
        @Override public AgentFeatureState disable() { enabled = false; return state(); }
        private AgentFeatureState state() {
            return new AgentFeatureState(AgentFeature.BASH, enabled, true, List.of(), Map.of());
        }
    }
}

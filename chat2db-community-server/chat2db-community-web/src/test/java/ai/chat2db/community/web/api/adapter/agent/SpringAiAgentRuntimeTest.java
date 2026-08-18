package ai.chat2db.community.web.api.adapter.agent;

import ai.chat2db.community.domain.api.enums.agent.AgentRuntimeTypeEnum;
import ai.chat2db.community.domain.api.model.agent.AgentRun;
import ai.chat2db.community.domain.api.model.agent.runtime.AgentRuntimeStartRequest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpringAiAgentRuntimeTest {

    private final SpringAiAgentRuntime runtime = new SpringAiAgentRuntime(null);

    @Test
    void shouldDeclareEmbeddedRuntimeCapabilities() {
        assertTrue(runtime.capabilities().isStreaming());
        assertTrue(runtime.capabilities().isToolCalling());
        assertTrue(runtime.capabilities().isCancellation());
        assertTrue(runtime.capabilities().isApprovalResume());
        assertFalse(runtime.capabilities().isSessionResume());
        assertFalse(runtime.capabilities().isExternalProcess());
    }

    @Test
    void shouldRejectMismatchedRuntimeTypeBeforeDispatch() {
        AgentRun run = new AgentRun();
        run.setId("run-1");
        run.setRuntimeType(AgentRuntimeTypeEnum.EXTERNAL_AGENT);
        AgentRuntimeStartRequest request = new AgentRuntimeStartRequest();
        request.setRun(run);

        assertThrows(IllegalArgumentException.class, () -> runtime.start(request, event -> { }));
    }

    @Test
    void shouldRejectMissingEventSinkBeforeDispatch() {
        AgentRun run = new AgentRun();
        run.setId("run-1");
        run.setRuntimeType(AgentRuntimeTypeEnum.EMBEDDED_SPRING_AI);
        AgentRuntimeStartRequest request = new AgentRuntimeStartRequest();
        request.setRun(run);

        assertThrows(IllegalArgumentException.class, () -> runtime.start(request, null));
    }
}

package ai.chat2db.community.domain.core.impl.agent.runtime;

import ai.chat2db.community.domain.api.enums.agent.AgentRuntimeTypeEnum;
import ai.chat2db.community.domain.api.model.agent.runtime.AgentRunHandle;
import ai.chat2db.community.domain.api.model.agent.runtime.AgentRuntimeCapabilities;
import ai.chat2db.community.domain.api.model.agent.runtime.AgentRuntimeResumeRequest;
import ai.chat2db.community.domain.api.model.agent.runtime.AgentRuntimeStartRequest;
import ai.chat2db.community.domain.api.service.agent.runtime.AgentEventSink;
import ai.chat2db.community.domain.api.service.agent.runtime.AgentRuntime;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.NoSuchElementException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AgentRuntimeRegistryTest {

    @Test
    void resolvesRuntimeByType() {
        AgentRuntime embedded = runtime(AgentRuntimeTypeEnum.EMBEDDED_SPRING_AI);
        AgentRuntimeRegistry registry = new AgentRuntimeRegistry(List.of(embedded));

        assertSame(embedded, registry.require(AgentRuntimeTypeEnum.EMBEDDED_SPRING_AI));
        assertEquals(List.of(AgentRuntimeTypeEnum.EMBEDDED_SPRING_AI), registry.registeredTypes());
        assertThrows(NoSuchElementException.class,
                () -> registry.require(AgentRuntimeTypeEnum.EXTERNAL_AGENT));
    }

    @Test
    void rejectsDuplicateRuntimeType() {
        assertThrows(IllegalStateException.class, () -> new AgentRuntimeRegistry(List.of(
                runtime(AgentRuntimeTypeEnum.EMBEDDED_SPRING_AI),
                runtime(AgentRuntimeTypeEnum.EMBEDDED_SPRING_AI))));
    }

    private static AgentRuntime runtime(AgentRuntimeTypeEnum type) {
        return new AgentRuntime() {
            @Override
            public AgentRuntimeTypeEnum type() {
                return type;
            }

            @Override
            public AgentRuntimeCapabilities capabilities() {
                return new AgentRuntimeCapabilities();
            }

            @Override
            public AgentRunHandle start(AgentRuntimeStartRequest request, AgentEventSink eventSink) {
                return new AgentRunHandle();
            }

            @Override
            public void resume(AgentRuntimeResumeRequest request, AgentEventSink eventSink) {
            }

            @Override
            public void cancel(String runId) {
            }
        };
    }
}

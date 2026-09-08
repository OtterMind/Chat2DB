package ai.chat2db.community.domain.core.impl.agent;

import ai.chat2db.community.domain.api.model.agent.AgentRuntimeType;
import ai.chat2db.community.tools.exception.agent.AgentRuntimeUnavailableException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AgentRuntimeRegistryTest {

    @Test
    void registersAndListsAdaptersByStableRuntimeId() {
        FakeAgentRuntimeAdapter pi = new FakeAgentRuntimeAdapter(AgentRuntimeType.PI);
        FakeAgentRuntimeAdapter codex = new FakeAgentRuntimeAdapter(AgentRuntimeType.CODEX);
        AgentRuntimeRegistry registry = new AgentRuntimeRegistry(List.of(pi, codex));

        assertSame(pi, registry.require(AgentRuntimeType.PI));
        assertEquals(List.of(AgentRuntimeType.PI, AgentRuntimeType.CODEX), registry.list().stream()
                .map(adapter -> adapter.descriptor().type())
                .toList());
    }

    @Test
    void missingRuntimeReturnsNullOrThrowsFromRequire() {
        AgentRuntimeRegistry registry = new AgentRuntimeRegistry(List.of());
        AgentRuntimeType runtimeType = AgentRuntimeType.PI;

        assertNull(registry.get(runtimeType));
        assertThrows(AgentRuntimeUnavailableException.class, () -> registry.require(runtimeType));
    }

    @Test
    void rejectsDuplicateRuntimeIds() {
        FakeAgentRuntimeAdapter first = new FakeAgentRuntimeAdapter(AgentRuntimeType.PI);
        FakeAgentRuntimeAdapter duplicate = new FakeAgentRuntimeAdapter(AgentRuntimeType.PI);

        assertThrows(IllegalStateException.class,
                () -> new AgentRuntimeRegistry(List.of(first, duplicate)));
    }
}

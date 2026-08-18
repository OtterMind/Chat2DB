package ai.chat2db.community.web.api.adapter.ai;

import ai.chat2db.community.domain.api.enums.agent.AgentCapabilityEnum;
import ai.chat2db.community.domain.api.model.agent.AgentDataScope;
import ai.chat2db.community.domain.api.model.agent.AgentDefinition;
import ai.chat2db.community.domain.api.model.agent.AgentRun;
import ai.chat2db.community.domain.api.model.agent.AgentTask;
import ai.chat2db.community.domain.api.model.agent.runtime.AgentRuntimeStartRequest;
import ai.chat2db.community.domain.api.service.ai.IAiAttachmentService;
import ai.chat2db.community.web.api.model.request.ai.ChatRequest;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiChatStreamAdapterAgentScopeTest {

    private final IAiAttachmentService attachmentService = (IAiAttachmentService) Proxy.newProxyInstance(
            IAiAttachmentService.class.getClassLoader(), new Class<?>[]{IAiAttachmentService.class},
            (proxy, method, arguments) -> method.getReturnType() == boolean.class ? false : null);
    private final AiChatStreamAdapter adapter = new AiChatStreamAdapter(
            null, null, new AiToolAdapter(null, null), null, null, attachmentService, null, null, null,
            Duration.ofMillis(25));

    @Test
    void enablesToolsWithoutPreselectingOneOfMultipleAuthorizedDatasources() {
        AgentRuntimeStartRequest runtimeRequest = runtimeRequest(List.of(
                scope(7L, "sales"), scope(8L, "warehouse")));

        ChatRequest request = adapter.agentChatRequest(runtimeRequest);

        assertTrue(request.getEnableTools());
        assertNull(request.getDataSourceId());
        assertNull(request.getDatabaseName());
        assertNull(request.getSchemaName());
    }

    @Test
    void failsAnAgentStreamThatStopsProducingEvents() {
        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> adapter.withAgentIdleTimeout(Flux.never()).blockLast());

        assertTrue(exception.getMessage().contains("received no data"));
    }

    @Test
    void preservesPlatformChartProtocolWhenAgentHasCustomInstructions() {
        ChatRequest request = new ChatRequest();
        request.setSystemPrompt("You are the Alpha business analyst.");

        String prompt = adapter.resolveSystemPrompt(request, Map.of("agentRunId", "run-1"));

        assertTrue(prompt.contains("You are Chat2DB AI assistant"));
        assertTrue(prompt.contains("You are the Alpha business analyst."));
        assertTrue(prompt.contains("Agent Artifact Output Protocol (Highest Priority)"));
        assertTrue(prompt.contains("Never draw charts with ASCII characters"));
        assertTrue(prompt.contains("Never output Mermaid syntax"));
        assertTrue(prompt.contains("\"chartType\":\"Pie\""));
    }

    private AgentRuntimeStartRequest runtimeRequest(List<AgentDataScope> scopes) {
        AgentDefinition agent = new AgentDefinition();
        agent.setCapabilities(new LinkedHashSet<>(List.of(AgentCapabilityEnum.DATA_READ)));
        AgentTask task = new AgentTask();
        task.setTitle("Compare sales and inventory");
        task.setDataScopeSnapshot(scopes);
        AgentRuntimeStartRequest request = new AgentRuntimeStartRequest();
        request.setAgent(agent);
        request.setTask(task);
        request.setRun(new AgentRun());
        return request;
    }

    private AgentDataScope scope(Long dataSourceId, String databaseName) {
        AgentDataScope scope = new AgentDataScope();
        scope.setDataSourceId(dataSourceId);
        scope.setDatabaseName(databaseName);
        return scope;
    }
}

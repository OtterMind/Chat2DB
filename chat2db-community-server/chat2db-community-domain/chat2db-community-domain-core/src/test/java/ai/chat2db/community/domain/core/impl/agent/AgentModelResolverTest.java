package ai.chat2db.community.domain.core.impl.agent;

import ai.chat2db.community.domain.api.model.ai.AiRuntimeModel;
import ai.chat2db.community.domain.api.model.request.ai.AiChatRuntimeResolveRequest;
import ai.chat2db.community.domain.api.service.ai.IAiModelConfigService;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Proxy;

import static org.junit.jupiter.api.Assertions.*;

class AgentModelResolverTest {

    @Test
    void resolvesModelSnapshotFromServerConfiguration() {
        AiRuntimeModel runtime = new AiRuntimeModel();
        runtime.setProvider("OPENAI");
        runtime.setModel("configured-model");
        runtime.setMaxTokens(4096);
        runtime.setApiKey("server-only-key");
        AiChatRuntimeResolveRequest[] captured = new AiChatRuntimeResolveRequest[1];
        IAiModelConfigService configs = (IAiModelConfigService) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{IAiModelConfigService.class}, (proxy, method, args) -> {
                    captured[0] = (AiChatRuntimeResolveRequest) args[0];
                    return runtime;
                });

        var snapshot = new AgentModelResolver(configs).resolve("saved-model");

        assertEquals("saved-model", snapshot.modelConfigId());
        assertEquals("configured-model", snapshot.modelId());
        assertEquals("OPENAI", snapshot.provider());
        assertEquals(4096, snapshot.maxOutputTokens());
        assertFalse(snapshot.toString().contains("server-only-key"));
        assertNull(captured[0].getModel());
        assertNull(captured[0].getApiKey());
    }

    @Test
    void rejectsMissingConfiguration() {
        IAiModelConfigService configs = (IAiModelConfigService) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{IAiModelConfigService.class},
                (proxy, method, args) -> null);
        assertThrows(IllegalArgumentException.class, () -> new AgentModelResolver(configs).resolve("missing"));
    }
}

package ai.chat2db.community.domain.core.impl.agent;

import ai.chat2db.community.domain.api.model.agent.AgentModelSnapshot;
import ai.chat2db.community.domain.api.model.ai.AiRuntimeModel;
import ai.chat2db.community.domain.api.model.request.ai.AiChatRuntimeResolveRequest;
import ai.chat2db.community.domain.api.service.ai.IAiModelConfigService;
import org.springframework.stereotype.Component;

@Component
public class AgentModelResolver {

    private final IAiModelConfigService modelConfigService;

    public AgentModelResolver(IAiModelConfigService modelConfigService) {
        this.modelConfigService = modelConfigService;
    }

    public AgentModelSnapshot resolve(String modelConfigId) {
        AiChatRuntimeResolveRequest request = new AiChatRuntimeResolveRequest();
        request.setModelConfigId(modelConfigId);
        AiRuntimeModel model = modelConfigService.resolveRuntimeModel(request);
        if (model == null) {
            throw new IllegalArgumentException("Agent model configuration is unavailable");
        }
        return new AgentModelSnapshot(
                modelConfigId, 1, model.getProvider(), model.getModel(), null, model.getMaxTokens());
    }
}

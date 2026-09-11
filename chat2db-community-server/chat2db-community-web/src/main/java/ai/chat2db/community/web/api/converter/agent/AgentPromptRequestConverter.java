package ai.chat2db.community.web.api.converter.agent;

import ai.chat2db.community.domain.api.model.request.agent.AgentRunStartCommand;
import ai.chat2db.community.domain.api.model.request.agent.AiAgentRunContextRequest;
import ai.chat2db.community.tools.model.agent.runtime.AgentRuntimeInput;
import ai.chat2db.community.web.api.model.request.agent.AgentRunContextRequest;
import ai.chat2db.community.web.api.model.request.agent.AgentRunStartRequest;
import java.util.List;
import org.mapstruct.Mapper;
import org.mapstruct.factory.Mappers;

@Mapper
public abstract class AgentPromptRequestConverter {
    public static final AgentPromptRequestConverter INSTANCE = Mappers.getMapper(AgentPromptRequestConverter.class);

    public AgentRunStartCommand request2command(Long userId, String sessionId, AgentRunStartRequest request) {
        return new AgentRunStartCommand(userId, sessionId, request.modelConfigId(),
                new AgentRuntimeInput(request.message(), List.of()), request.idempotencyKey(), context2request(request.context()));
    }

    public abstract AiAgentRunContextRequest context2request(AgentRunContextRequest request);
}

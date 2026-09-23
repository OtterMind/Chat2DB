package ai.chat2db.community.domain.api.service.agent;

import ai.chat2db.community.domain.api.model.agent.context.AiAgentRunContext;
import ai.chat2db.community.domain.api.model.request.agent.AiAgentRunContextRequest;

public interface IAiAgentContextService {
    AiAgentRunContext resolve(AiAgentRunContextRequest aiAgentRunContextRequest);
}

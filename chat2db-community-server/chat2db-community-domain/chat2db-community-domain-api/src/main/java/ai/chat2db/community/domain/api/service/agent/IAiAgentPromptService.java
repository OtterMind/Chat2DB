package ai.chat2db.community.domain.api.service.agent;

import ai.chat2db.community.domain.api.model.agent.context.AiAgentRunContext;

public interface IAiAgentPromptService {
    String systemPrompt();

    String userPrompt(String userMessage, AiAgentRunContext context);
}

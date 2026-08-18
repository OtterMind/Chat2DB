package ai.chat2db.community.web.api.model.response.agent;

import ai.chat2db.community.domain.api.model.ai.AiChatMessage;
import lombok.Data;

@Data
public class AgentChatTaskCreateResponse {

    private String sessionId;

    private AiChatMessage message;

    private AgentTaskDetailResponse taskDetail;
}

package ai.chat2db.community.domain.api.model.agent;

import ai.chat2db.community.domain.api.model.ai.AiChatMessage;
import ai.chat2db.community.domain.api.model.ai.AiChatSession;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AgentChatTaskCreation {

    private AiChatSession session;

    private AiChatMessage message;

    private AgentTaskCreation taskCreation;
}

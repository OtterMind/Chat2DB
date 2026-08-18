package ai.chat2db.community.domain.api.model.agent;

import lombok.Data;

@Data
public class AgentInboundMessageResult {
    private AgentExternalConversationBinding binding;
    private AgentInboundMessage inboundMessage;
    private AgentChatTaskCreation taskCreation;
    private Boolean duplicate;
    private String conversationLinkStatus;
    private String taskLinkStatus;
}

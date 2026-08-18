package ai.chat2db.community.domain.api.model.request.agent;

import ai.chat2db.community.domain.api.model.agent.AgentDataScope;
import ai.chat2db.community.domain.api.model.ai.ChatAttachment;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class AgentChatTaskCreateRequest {

    private String sessionId;

    private String messageId;

    private String content;

    private String taskDescription;

    private String assigneeAgentId;

    private Long createdBy;

    private List<AgentDataScope> dataScopeSnapshot = new ArrayList<>();

    private List<ChatAttachment> attachments = new ArrayList<>();
}

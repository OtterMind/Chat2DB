package ai.chat2db.community.domain.api.model.ai;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Data
public class AiChatMessage {

    private String id;

    private String sessionId;

    private String role;

    private String content;

    private String reasoningContent;

    private List<ChatAttachment> attachments = new ArrayList<>();

    private String messageType;

    private String taskId;

    private String agentId;

    private String agentName;

    private LocalDateTime gmtCreate;
}

package ai.chat2db.community.domain.api.model.request.ai;

import ai.chat2db.community.domain.api.model.ai.ChatAttachment;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

@Data
public class AiChatMessageAddRequest {

    private String id;

    @NotBlank
    private String sessionId;

    @NotNull
    private Long userId;

    @NotBlank
    private String role;

    private String content;

    private String reasoningContent;

    private List<ChatAttachment> attachments;

    private String messageType;

    private String taskId;

    private String agentId;

    private String agentName;
}

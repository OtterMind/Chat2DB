package ai.chat2db.community.domain.api.model.ai;

import ai.chat2db.community.domain.api.model.request.ai.AiSelectedKnowledge;
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

    private List<AiSelectedKnowledge> selectedKnowledge = new ArrayList<>();

    private LocalDateTime gmtCreate;
}

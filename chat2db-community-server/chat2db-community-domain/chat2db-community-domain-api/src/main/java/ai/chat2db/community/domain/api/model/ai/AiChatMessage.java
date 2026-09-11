package ai.chat2db.community.domain.api.model.ai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class AiChatMessage {

    private String id;

    private String sessionId;

    private String role;

    private String content;

    private String reasoningContent;

    private List<ChatAttachment> attachments = new ArrayList<>();

    private LocalDateTime gmtCreate;
}

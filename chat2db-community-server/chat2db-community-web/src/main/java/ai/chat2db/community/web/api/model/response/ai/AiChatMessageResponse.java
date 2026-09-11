package ai.chat2db.community.web.api.model.response.ai;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Data
public class AiChatMessageResponse {

    private String id;

    private String sessionId;

    private String role;

    private String content;

    private String reasoningContent;

    private List<ai.chat2db.community.domain.api.model.ai.ChatAttachment> attachments = new ArrayList<>();

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime gmtCreate;
}

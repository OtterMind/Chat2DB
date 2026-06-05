package ai.chat2db.server.web.api.controller.ai.conversation.vo;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class AiMessageVO {

    private String messageId;
    private String role;
    private String content;
    private String thinking;
    private String promptType;
    private Integer sequenceNo;
    private LocalDateTime gmtCreate;
}

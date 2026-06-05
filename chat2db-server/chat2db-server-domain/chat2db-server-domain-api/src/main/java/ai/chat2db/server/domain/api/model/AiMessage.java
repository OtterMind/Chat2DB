package ai.chat2db.server.domain.api.model;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * AI 消息 DTO
 */
@Data
public class AiMessage {

    private Long id;
    private LocalDateTime gmtCreate;

    private String conversationId;
    private Long userId;
    private String messageId;
    private String role;
    private String content;
    private String thinking;
    private String promptType;
    private String sqlExtracted;
    private Integer sequenceNo;
}

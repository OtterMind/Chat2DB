package ai.chat2db.server.domain.api.model;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * AI 会话 DTO
 */
@Data
public class AiConversation {

    private Long id;
    private LocalDateTime gmtCreate;
    private LocalDateTime gmtModified;

    private String conversationId;
    private Long userId;
    private String title;
    private Long dataSourceId;
    private String dataSourceName;
    private String databaseName;
    private String schemaName;
    private Integer messageCount;
    private String lastMessagePreview;
    private String status;
}

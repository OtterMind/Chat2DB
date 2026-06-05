package ai.chat2db.server.web.api.controller.ai.conversation.vo;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class AiConversationVO {

    private Long id;
    private String conversationId;
    private String title;
    private Long dataSourceId;
    private String dataSourceName;
    private String databaseName;
    private String schemaName;
    private Integer messageCount;
    private String lastMessagePreview;
    private String status;
    private LocalDateTime gmtCreate;
    private LocalDateTime gmtModified;
}

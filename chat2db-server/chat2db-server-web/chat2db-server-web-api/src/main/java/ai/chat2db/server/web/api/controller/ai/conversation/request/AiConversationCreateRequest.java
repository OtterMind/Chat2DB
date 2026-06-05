package ai.chat2db.server.web.api.controller.ai.conversation.request;

import lombok.Data;

@Data
public class AiConversationCreateRequest {

    private String conversationId;
    private Long dataSourceId;
    private String databaseName;
    private String schemaName;
    private String title;
}

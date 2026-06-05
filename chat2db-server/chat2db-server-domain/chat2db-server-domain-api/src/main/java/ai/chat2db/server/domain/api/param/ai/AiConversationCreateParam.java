package ai.chat2db.server.domain.api.param.ai;

import lombok.Data;

/**
 * 新建 AI 会话
 */
@Data
public class AiConversationCreateParam {

    private Long dataSourceId;
    private String databaseName;
    private String schemaName;
    private String title;
    private Long userId;
}

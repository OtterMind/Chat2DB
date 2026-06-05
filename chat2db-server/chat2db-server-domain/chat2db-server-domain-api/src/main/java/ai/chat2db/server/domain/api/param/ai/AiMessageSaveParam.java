package ai.chat2db.server.domain.api.param.ai;

import lombok.Data;

/**
 * 单条 AI 消息写入(流式完成后)
 */
@Data
public class AiMessageSaveParam {

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

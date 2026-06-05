package ai.chat2db.server.domain.api.model;

import lombok.Data;

import java.util.List;

/**
 * AI 会话详情(含完整消息)
 */
@Data
public class AiConversationDetail {

    private AiConversation conversation;
    private List<AiMessage> messages;
}

package ai.chat2db.server.web.api.controller.ai.conversation.vo;

import lombok.Data;

import java.util.List;

@Data
public class AiConversationDetailVO {

    private AiConversationVO conversation;
    private List<AiMessageVO> messages;
}

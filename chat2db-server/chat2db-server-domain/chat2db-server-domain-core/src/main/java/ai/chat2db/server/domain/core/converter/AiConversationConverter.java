package ai.chat2db.server.domain.core.converter;

import java.util.List;

import ai.chat2db.server.domain.api.model.AiConversation;
import ai.chat2db.server.domain.api.model.AiMessage;
import ai.chat2db.server.domain.api.param.ai.AiConversationCreateParam;
import ai.chat2db.server.domain.api.param.ai.AiMessageSaveParam;
import ai.chat2db.server.domain.repository.entity.AiConversationDO;
import ai.chat2db.server.domain.repository.entity.AiMessageDO;
import org.mapstruct.Mapper;

/**
 * AI 会话/消息 转换器
 */
@Mapper(componentModel = "spring")
public abstract class AiConversationConverter {

    public abstract AiConversationDO createParam2do(AiConversationCreateParam param);

    public abstract AiConversation do2dto(AiConversationDO conversationDO);

    public abstract List<AiConversation> do2dto(List<AiConversationDO> conversationDOS);

    public abstract AiMessage do2MessageDto(AiMessageDO messageDO);

    public abstract List<AiMessage> do2MessageDto(List<AiMessageDO> messageDOS);

    public abstract AiMessageDO saveParam2do(AiMessageSaveParam param);
}

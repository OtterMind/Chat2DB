package ai.chat2db.server.web.api.controller.ai.conversation.converter;

import java.util.List;

import ai.chat2db.server.domain.api.model.AiConversation;
import ai.chat2db.server.domain.api.model.AiConversationDetail;
import ai.chat2db.server.domain.api.model.AiMessage;
import ai.chat2db.server.domain.api.param.ai.AiConversationCreateParam;
import ai.chat2db.server.domain.api.param.ai.AiConversationQueryParam;
import ai.chat2db.server.web.api.controller.ai.conversation.request.AiConversationCreateRequest;
import ai.chat2db.server.web.api.controller.ai.conversation.request.AiConversationQueryRequest;
import ai.chat2db.server.web.api.controller.ai.conversation.vo.AiConversationDetailVO;
import ai.chat2db.server.web.api.controller.ai.conversation.vo.AiConversationVO;
import ai.chat2db.server.web.api.controller.ai.conversation.vo.AiMessageVO;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Mappings;
import org.mapstruct.factory.Mappers;

@Mapper(componentModel = "spring")
public abstract class AiConversationWebConverter {

    public abstract AiConversationCreateParam req2param(AiConversationCreateRequest request);

    @Mappings({
        @Mapping(target = "userId", ignore = true),
        @Mapping(target = "status", ignore = true)
    })
    public abstract AiConversationQueryParam queryReq2param(AiConversationQueryRequest request);

    public abstract AiConversationVO dto2vo(AiConversation conversation);

    public abstract List<AiConversationVO> dto2vo(List<AiConversation> conversations);

    public abstract AiMessageVO dto2MessageVo(AiMessage message);

    public abstract List<AiMessageVO> dto2MessageVo(List<AiMessage> messages);

    public abstract AiConversationDetailVO detail2vo(AiConversationDetail detail);

    public AiConversationDetailVO detail2voWithMessages(AiConversationDetail detail) {
        if (detail == null) {
            return null;
        }
        AiConversationDetailVO vo = detail2vo(detail);
        if (vo != null && detail.getMessages() != null) {
            vo.setMessages(dto2MessageVo(detail.getMessages()));
        }
        return vo;
    }
}

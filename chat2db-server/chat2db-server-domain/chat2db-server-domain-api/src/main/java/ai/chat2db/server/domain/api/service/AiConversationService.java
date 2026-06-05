package ai.chat2db.server.domain.api.service;

import ai.chat2db.server.domain.api.model.AiConversation;
import ai.chat2db.server.domain.api.model.AiConversationDetail;
import ai.chat2db.server.domain.api.model.AiMessage;
import ai.chat2db.server.domain.api.param.ai.AiConversationCreateParam;
import ai.chat2db.server.domain.api.param.ai.AiConversationQueryParam;
import ai.chat2db.server.domain.api.param.ai.AiMessageSaveParam;
import ai.chat2db.server.tools.base.wrapper.ServicePage;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * AI 会话服务
 */
public interface AiConversationService {

    /**
     * 新建一个空会话
     *
     * @return conversationId
     */
    String create(@NotNull AiConversationCreateParam param);

    /**
     * 重命名
     */
    void updateTitle(@NotNull String conversationId, @NotNull String title);

    /**
     * 软删
     */
    void deleteWithPermission(@NotNull String conversationId, @NotNull Long userId);

    /**
     * 列表(分页,按用户隔离)
     */
    ServicePage<AiConversation> queryPage(@NotNull AiConversationQueryParam param);

    /**
     * 详情(含消息)
     */
    AiConversationDetail getDetail(@NotNull String conversationId, @NotNull Long userId);

    /**
     * 单条查询
     */
    AiConversation findByConversationId(@NotNull String conversationId);

    /**
     * 追加单条消息
     */
    void appendMessage(@NotNull AiMessageSaveParam param);

    /**
     * 流式完成后,落库一对 user/assistant 消息并更新会话元数据
     */
    void appendMessageTurn(@NotNull String conversationId,
                           @NotNull Long userId,
                           @NotNull String userMessageId,
                           @NotNull String userContent,
                           @NotNull String assistantMessageId,
                           @NotNull String assistantContent,
                           @NotNull String assistantThinking,
                           @NotNull String promptType,
                           @NotNull String sqlExtracted);

    /**
     * 读取最近 N 条消息(revision 模式用)
     */
    List<AiMessage> listRecentMessages(@NotNull String conversationId, int limit);
}

package ai.chat2db.server.web.api.controller.ai.statemachine;

import java.util.List;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import ai.chat2db.server.tools.common.model.LoginUser;
import ai.chat2db.server.web.api.controller.ai.request.ChatQueryRequest;
import ai.chat2db.spi.sql.ConnectInfo;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ChatContext {
    private String uid;
    private ChatQueryRequest request;
    private SseEmitter sseEmitter;
    private ChatClient chatClient;
    private String builtPrompt;
    private List<String> selectedTables;
    private String schemaDdl;
    private String explainSql;
    private String explainResult;
    private volatile boolean cancelled;
    private LoginUser loginUser;
    private ConnectInfo connectInfo;

    private String currentContent;
    private String currentThinking;

    /**
     * 用户消息的客户端 UUID(落库用)
     */
    private String userMessageId;

    /**
     * 标记本轮是否为该会话的首条消息(用于触发标题生成)
     */
    private Boolean firstTurnOfConversation;
}
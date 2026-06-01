package ai.chat2db.server.web.api.controller.ai.statemachine.actions;

import ai.chat2db.server.domain.api.service.DataSourceService;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.statemachine.StateContext;
import org.springframework.stereotype.Component;

import ai.chat2db.server.web.api.controller.ai.enums.PromptType;
import ai.chat2db.server.web.api.controller.ai.prompt.PromptBuilder;
import ai.chat2db.server.web.api.controller.ai.prompt.PromptContext;
import ai.chat2db.server.web.api.controller.ai.request.ChatQueryRequest;
import ai.chat2db.server.web.api.controller.ai.service.AiConversationCache;
import ai.chat2db.server.web.api.controller.ai.statemachine.ChatContext;
import ai.chat2db.server.web.api.controller.ai.statemachine.ChatEvent;
import ai.chat2db.server.web.api.controller.ai.statemachine.ChatState;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

/**
 * 构建提示词动作
 */
@Component
@Slf4j
public class BuildPromptAction extends BaseChatAction {

    @Autowired
    private PromptBuilder promptBuilder;

    @Autowired
    private DataSourceService dataSourceService;

    @Autowired
    private AiConversationCache aiConversationCache;

    @Override
    public void execute(StateContext<ChatState, ChatEvent> context) {
        ChatContext chatContext = getChatContext(context);
        if (chatContext.isCancelled()) {
            log.info("[BuildPromptAction] Skip cancelled request, uid: {}", chatContext.getUid());
            return;
        }

        sendStateEvent(chatContext.getSseEmitter(),
                ChatState.BUILDING_PROMPT, "正在构建提示...");

        buildContext(chatContext);
        try {
            ChatQueryRequest request = chatContext.getRequest();
            String schemaDdl = chatContext.getSchemaDdl();

            PromptType promptType = determinePromptType(request);
            log.info("[BuildPromptAction] Building prompt, uid: {}, promptType: {}, isRevision: {}, messageLength: {}",
                    chatContext.getUid(), promptType, request.getIsRevision(),
                    StringUtils.length(request.getMessage()));

            // 解析 ext 中的 sourceFields（用于字段映射）
            String sourceFields = extractSourceFields(request.getExt());
            String history = request.getHistory();
            String previousSql = request.getPreviousSql();
            if (PromptType.NL_2_SQL == promptType && Boolean.TRUE.equals(request.getIsRevision())) {
                if (StringUtils.isBlank(history)) {
                    history = aiConversationCache.getHistoryJson(request.getConversationId());
                }
                if (StringUtils.isBlank(previousSql)) {
                    previousSql = aiConversationCache.getPreviousSql(request.getConversationId());
                }
            }

            PromptContext promptContext = PromptContext.builder()
                    .promptType(promptType)
                    .message(request.getMessage())
                    .ext(request.getExt())
                    .schemaDdl(schemaDdl)
                    .explainPlan(chatContext.getExplainResult())
                    .dataSourceType(dataSourceService.queryDatabaseType(request.getDataSourceId()))
                    .targetSqlType(request.getDestSqlType())
                    .sourceFields(sourceFields)
                    .conversationId(request.getConversationId())
                    .history(history)
                    .previousSql(previousSql)
                    .isRevision(request.getIsRevision())
                    .build();

            String builtPrompt = promptBuilder.context(promptContext).build();
            chatContext.setBuiltPrompt(builtPrompt);
            log.info("[BuildPromptAction] Prompt built, uid: {}, promptLength: {}",
                    chatContext.getUid(), StringUtils.length(builtPrompt));
            log.info("[BuildPromptAction] Prompt content, uid: {}:\n{}", chatContext.getUid(), builtPrompt);

            context.getStateMachine().sendEvent(
                    Mono.just(MessageBuilder.withPayload(ChatEvent.PROMPT_BUILT).build())
            ).subscribe();
        } catch (Exception e) {
            log.error("[BuildPromptAction] Build prompt failed for uid: {}", chatContext.getUid(), e);
            sendError(chatContext.getSseEmitter(),
                    "构建提示失败：" + e.getMessage());
            context.getStateMachine().sendEvent(
                    Mono.just(MessageBuilder.withPayload(ChatEvent.PROMPT_BUILD_FAILED).build())
            ).subscribe();
        } finally {
            removeContext();
        }
    }

    private PromptType determinePromptType(ChatQueryRequest request) {
        String promptType = StringUtils.isBlank(request.getPromptType())
                ? PromptType.NL_2_SQL.getCode()
                : request.getPromptType();
        return PromptType.valueOf(promptType);
    }

    private String extractSourceFields(String ext) {
        if (StringUtils.isBlank(ext)) {
            return null;
        }
        try {
            com.alibaba.fastjson2.JSONObject extJson = com.alibaba.fastjson2.JSON.parseObject(ext);
            if (extJson.containsKey("sourceFields")) {
                return extJson.getString("sourceFields");
            }
        } catch (Exception e) {
            log.warn("[BuildPromptAction] Failed to parse sourceFields from ext", e);
        }
        return null;
    }
}

package ai.chat2db.server.web.api.controller.ai.statemachine.actions;

import java.util.List;

import ai.chat2db.server.domain.api.model.AiMessage;
import ai.chat2db.server.domain.api.service.AiConversationService;
import ai.chat2db.server.domain.api.service.DataSourceService;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.statemachine.StateContext;
import org.springframework.stereotype.Component;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;

import ai.chat2db.server.web.api.controller.ai.enums.PromptType;
import ai.chat2db.server.web.api.controller.ai.prompt.PromptBuilder;
import ai.chat2db.server.web.api.controller.ai.prompt.PromptContext;
import ai.chat2db.server.web.api.controller.ai.prompt.PreviousSqlResolver;
import ai.chat2db.server.web.api.controller.ai.request.ChatQueryRequest;
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

    private static final int MAX_HISTORY_MESSAGES = 6;

    @Autowired
    private PromptBuilder promptBuilder;

    @Autowired
    private DataSourceService dataSourceService;

    @Autowired
    private AiConversationService aiConversationService;

    @Autowired
    private PreviousSqlResolver previousSqlResolver;

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

            String sourceFields = extractSourceFields(request.getExt());
            String history = request.getHistory();
            String previousSql = request.getPreviousSql();
            if (PromptType.NL_2_SQL == promptType && Boolean.TRUE.equals(request.getIsRevision())) {
                if (StringUtils.isBlank(history) && StringUtils.isNotBlank(request.getConversationId())) {
                    history = loadHistoryFromDb(request.getConversationId());
                }
                previousSql = previousSqlResolver.resolve(request);
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

    private String loadHistoryFromDb(String conversationId) {
        try {
            List<AiMessage> recent = aiConversationService.listRecentMessages(conversationId, MAX_HISTORY_MESSAGES);
            if (recent == null || recent.isEmpty()) {
                return null;
            }
            JSONArray array = new JSONArray();
            for (AiMessage msg : recent) {
                if ("assistant".equals(msg.getRole())) {
                    continue;
                }
                JSONObject obj = new JSONObject();
                obj.put("role", msg.getRole());
                obj.put("content", msg.getContent());
                array.add(obj);
            }
            return JSON.toJSONString(array);
        } catch (Exception e) {
            log.warn("[BuildPromptAction] Failed to load history for {}: {}", conversationId, e.getMessage());
            return null;
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

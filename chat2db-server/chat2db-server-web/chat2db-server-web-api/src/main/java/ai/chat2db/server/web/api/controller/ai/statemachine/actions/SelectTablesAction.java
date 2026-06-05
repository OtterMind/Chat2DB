package ai.chat2db.server.web.api.controller.ai.statemachine.actions;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import ai.chat2db.server.web.api.config.AiChatConfig;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.statemachine.StateContext;
import org.springframework.stereotype.Component;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;

import ai.chat2db.server.domain.api.service.DatabaseService;
import ai.chat2db.server.web.api.controller.ai.enums.PromptType;
import ai.chat2db.server.web.api.controller.ai.prompt.PromptBuilder;
import ai.chat2db.server.web.api.controller.ai.prompt.PromptContext;
import ai.chat2db.server.web.api.controller.ai.prompt.PreviousSqlResolver;
import ai.chat2db.server.web.api.controller.ai.statemachine.ChatContext;
import ai.chat2db.server.web.api.controller.ai.statemachine.ChatEvent;
import ai.chat2db.server.web.api.controller.ai.statemachine.ChatState;
import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

/**
 * 自动选择表动作
 */
@Component
@Slf4j
public class SelectTablesAction extends BaseChatAction {

    @Autowired
    private PromptBuilder promptBuilder;

    @Autowired
    private DatabaseService databaseService;

    @Autowired
    private AiChatConfig aiChatConfig;

    @Autowired
    private PreviousSqlResolver previousSqlResolver;

    @Override
    public void execute(StateContext<ChatState, ChatEvent> context) {
        log.info("[SelectTablesAction] execute called");
        ChatContext ctx = getChatContext(context);
        log.info("[SelectTablesAction] uid: {}, cancelled: {}", ctx.getUid(), ctx.isCancelled());
        if (ctx.isCancelled()) {
            log.info("[SelectTablesAction] cancelled, returning");
            return;
        }

        sendStateEvent(ctx.getSseEmitter(),
                ChatState.AUTO_SELECTING_TABLES, "正在选择相关表...");

        buildContext(ctx);
        try {
            log.info("[SelectTablesAction] Starting table selection for uid: {}", ctx.getUid());
            List<String> tableNames = selectTables(ctx);
            log.info("[SelectTablesAction] Selected tables: {}", tableNames);

            if (CollectionUtils.isNotEmpty(tableNames)) {
                ctx.getRequest().setTableNames(tableNames);
                ctx.setSelectedTables(tableNames);
                sendTablesSelected(ctx.getSseEmitter(), tableNames);
            }

            log.info("[SelectTablesAction] Sending AUTO_SELECT_DONE event for uid: {}", ctx.getUid());
            context.getStateMachine().sendEvent(
                    Mono.just(MessageBuilder.withPayload(ChatEvent.AUTO_SELECT_DONE).build())
            ).subscribe();
        } catch (Exception e) {
            log.error("[SelectTablesAction] Auto select tables failed for uid: {}", ctx.getUid(), e);
            sendError(ctx.getSseEmitter(), "选表失败：" + e.getMessage());
            context.getStateMachine().sendEvent(
                    Mono.just(MessageBuilder.withPayload(ChatEvent.AUTO_SELECT_FAILED).build())
            ).subscribe();
        } finally {
            removeContext();
        }
    }

    private List<String> selectTables(ChatContext ctx) {
        String selectPrompt = buildSelectPrompt(ctx);
        log.info("[SelectTablesAction] Select prompt for uid: {}:\n{}", ctx.getUid(), selectPrompt);
        ChatResponse chatResponse = aiChatConfig.createFastChatClient()
                .prompt(selectPrompt)
                .call()
                .chatResponse();

        String content = extractContent(chatResponse);
        log.info("[SelectTablesAction] AI response for uid: {}:\n{}", ctx.getUid(), content);
        return parseTableNames(content, ctx.getRequest().getTableNames());
    }

    private String buildSelectPrompt(ChatContext ctx) {
        String schemaDdl = databaseService.queryDatabaseTables(
                ctx.getRequest().getDataSourceId(),
                ctx.getRequest().getDatabaseName(),
                ctx.getRequest().getSchemaName()
        );

        PromptContext promptContext = PromptContext.builder()
                .promptType(PromptType.SELECT_TABLES)
                .message(ctx.getRequest().getMessage())
                .schemaDdl(schemaDdl)
                .previousSql(previousSqlResolver.resolve(ctx.getRequest()))
                .build();

        return promptBuilder.context(promptContext).build();
    }

    private String extractContent(ChatResponse chatResponse) {
        if (chatResponse == null || chatResponse.getResult() == null
                || chatResponse.getResult().getOutput() == null) {
            return null;
        }
        return chatResponse.getResult().getOutput().getText();
    }

    private List<String> parseTableNames(String content, List<String> existingTableNames) {
        if (StrUtil.isBlank(content)) {
            return null;
        }

        String json = normalizeJson(content);

        try {
            JSONObject obj = JSONObject.parseObject(json);
            JSONArray tableNames = obj.getJSONArray("table_names");
            if (tableNames == null || tableNames.isEmpty()) {
                return null;
            }
            return tableNames.toJavaList(String.class);
        } catch (Exception ignored) {
        }

        return fallbackParseTableNames(content, existingTableNames);
    }

    private String normalizeJson(String content) {
        String json = content.trim();
        if (!json.startsWith("{") && json.contains("{") && json.contains("}")) {
            json = json.substring(json.indexOf('{'), json.lastIndexOf('}') + 1);
        }
        return json;
    }

    private List<String> fallbackParseTableNames(String content, List<String> existingTableNames) {
        if (existingTableNames == null || existingTableNames.isEmpty()) {
            return null;
        }
        return existingTableNames.stream()
                .filter(name -> StringUtils.containsIgnoreCase(content, name))
                .toList();
    }
}

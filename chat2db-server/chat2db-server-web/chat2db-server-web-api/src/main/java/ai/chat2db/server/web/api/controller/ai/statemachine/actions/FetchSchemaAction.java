package ai.chat2db.server.web.api.controller.ai.statemachine.actions;

import java.util.concurrent.CompletableFuture;

import org.apache.commons.collections4.CollectionUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.statemachine.StateContext;
import org.springframework.stereotype.Component;

import ai.chat2db.server.domain.api.service.DatabaseService;
import ai.chat2db.server.domain.api.service.DataSourceService;
import ai.chat2db.server.tools.base.enums.DataSourceTypeEnum;
import ai.chat2db.server.web.api.controller.ai.request.ChatQueryRequest;
import ai.chat2db.server.web.api.controller.ai.statemachine.ChatContext;
import ai.chat2db.server.web.api.controller.ai.statemachine.ChatEvent;
import ai.chat2db.server.web.api.controller.ai.statemachine.ChatState;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

/**
 * 获取表结构动作
 */
@Component
@Slf4j
public class FetchSchemaAction extends BaseChatAction {

    @Autowired
    private DatabaseService databaseService;

    @Autowired
    private DataSourceService dataSourceService;

    @Override
    public void execute(StateContext<ChatState, ChatEvent> context) {
        log.info("[FetchSchemaAction] execute called");
        ChatContext ctx = getChatContext(context);
        log.info("[FetchSchemaAction] uid: {}, cancelled: {}", ctx.getUid(), ctx.isCancelled());
        if (ctx.isCancelled()) {
            log.info("[FetchSchemaAction] cancelled, returning");
            return;
        }

        sendStateEvent(ctx.getSseEmitter(),
                ChatState.FETCHING_TABLE_SCHEMA, "正在获取表结构...");

        buildContext(ctx);
        try {
            log.info("[FetchSchemaAction] Starting schema fetch for uid: {}, tableNames: {}",
                    ctx.getUid(), ctx.getRequest().getTableNames());
            String schemaDdl = fetchSchemaDdl(ctx);
            log.info("[FetchSchemaAction] Schema DDL length: {}", schemaDdl != null ? schemaDdl.length() : 0);
            ctx.setSchemaDdl(schemaDdl);

            if (CollectionUtils.isNotEmpty(ctx.getRequest().getTableNames())) {
                sendSchemaFetched(ctx.getSseEmitter(), schemaDdl);
            }

            log.info("[FetchSchemaAction] Sending SCHEMA_FETCHED event for uid: {}", ctx.getUid());
            context.getStateMachine().sendEvent(
                    Mono.just(MessageBuilder.withPayload(ChatEvent.SCHEMA_FETCHED).build())
            ).subscribe();
        } catch (Exception e) {
            log.error("[FetchSchemaAction] Fetch schema failed for uid: {}", ctx.getUid(), e);
            sendError(ctx.getSseEmitter(), "获取表结构失败：" + e.getMessage());
            context.getStateMachine().sendEvent(
                    Mono.just(MessageBuilder.withPayload(ChatEvent.FETCH_SCHEMA_FAILED).build())
            ).subscribe();
        } finally {
            removeContext();
        }
    }

    private String fetchSchemaDdl(ChatContext ctx) {
        ChatQueryRequest request = ctx.getRequest();

        if (isRedis(request)) {
            return "";
        }

        if (hasTableNames(request)) {
            return queryTablesWithNames(request);
        } else {
            return queryAllTables(request);
        }
    }

    private boolean isRedis(ChatQueryRequest request) {
        try {
            return DataSourceTypeEnum.REDIS.getCode().equalsIgnoreCase(
                    dataSourceService.queryDatabaseType(request.getDataSourceId()));
        } catch (Exception e) {
            log.warn("[FetchSchemaAction] Query database type failed, dataSourceId: {}, error: {}",
                    request.getDataSourceId(), e.getMessage());
            return false;
        }
    }

    private boolean hasTableNames(ChatQueryRequest request) {
        return CollectionUtils.isNotEmpty(request.getTableNames());
    }

    private String queryTablesWithNames(ChatQueryRequest request) {
        return databaseService.buildTableColumn(
                request.getDataSourceId(),
                request.getDatabaseName(),
                request.getSchemaName(),
                request.getTableNames()
        );
    }

    private String queryAllTables(ChatQueryRequest request) {
        return databaseService.queryDatabaseTables(
                request.getDataSourceId(),
                request.getDatabaseName(),
                request.getSchemaName()
        );
    }
}

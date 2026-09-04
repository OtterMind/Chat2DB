package ai.chat2db.community.web.api.adapter.db.execution;

import ai.chat2db.community.domain.api.enums.operation.SqlOperationLogStatusEnum;
import ai.chat2db.community.domain.api.model.runtime.ConnectionProfile;
import ai.chat2db.community.domain.api.model.request.runtime.DbConnectionContextRequest;
import ai.chat2db.community.domain.api.model.request.db.DbDlExecuteRequest;
import ai.chat2db.community.domain.api.model.request.db.DbStreamingExecuteRequest;
import ai.chat2db.community.domain.api.service.db.IDbConnectionContextService;
import ai.chat2db.community.domain.api.service.db.IDbExecuteResultEnhanceService;
import ai.chat2db.community.tools.http.LocalCookie;
import ai.chat2db.community.tools.model.Context;
import ai.chat2db.community.tools.util.ContextUtils;
import ai.chat2db.community.tools.util.I18nUtils;
import ai.chat2db.community.web.api.config.console.ConsoleHelper;
import ai.chat2db.community.domain.api.model.operation.SqlOperationLogRecord;
import ai.chat2db.community.domain.api.service.ops.IOpsSqlOperationLogService;
import ai.chat2db.community.domain.api.enums.operation.SqlOperationLogSourceEnum;
import ai.chat2db.community.domain.api.service.db.IDbLargeValueTokenService;
import ai.chat2db.community.web.api.converter.db.DbWebConverter;
import ai.chat2db.community.domain.api.service.db.ISqlExecutionStatementListener;
import ai.chat2db.community.domain.api.service.db.IDbSqlExecutionService;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import java.sql.SQLWarning;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

@Slf4j
public class SqlExecutionJob implements Runnable, ISqlExecutionStatementListener {

    @Getter
    private final SqlExecutionRequest request;
    private final ISqlExecutionSink sink;
    private final IDbConnectionContextService connectionContextService;
    private final IDbSqlExecutionService sqlExecutionService;
    private final DbWebConverter dbWebConverter;
    private final IDbLargeValueTokenService largeValueTokenService;
    private final IDbExecuteResultEnhanceService executeResultEnhanceService;
    private final IOpsSqlOperationLogService sqlOperationLogRecorder;
    private final Consumer<SqlExecutionJob> finishCallback;
    private final AtomicBoolean canceled = new AtomicBoolean(false);
    private final SqlExecutionEventContext eventContext = new SqlExecutionEventContext();

    @Setter
    @Getter
    private volatile Future<?> future;
    private volatile Statement currentStatement;
    private volatile Thread workerThread;

    public SqlExecutionJob(SqlExecutionRequest request, ISqlExecutionSink sink,
                           IDbConnectionContextService connectionContextService,
                           IDbSqlExecutionService sqlExecutionService,
                           DbWebConverter dbWebConverter,
                           IDbLargeValueTokenService largeValueTokenService,
                           IDbExecuteResultEnhanceService executeResultEnhanceService,
                           IOpsSqlOperationLogService sqlOperationLogRecorder,
                           Consumer<SqlExecutionJob> finishCallback) {
        this.request = request;
        this.sink = sink;
        this.connectionContextService = connectionContextService;
        this.sqlExecutionService = sqlExecutionService;
        this.dbWebConverter = dbWebConverter;
        this.largeValueTokenService = largeValueTokenService;
        this.executeResultEnhanceService = executeResultEnhanceService;
        this.sqlOperationLogRecorder = sqlOperationLogRecorder;
        this.finishCallback = finishCallback;
    }

    @Override
    public void run() {
        workerThread = Thread.currentThread();
        Long consoleId = consoleId();
        if (consoleId == null) {
            runIfNotCanceled();
            return;
        }
        try {
            connectionContextService.withConsoleTransactionLock(consoleId, () -> {
                runIfNotCanceled();
                return null;
            });
        } catch (Exception e) {
            throw e instanceof RuntimeException runtimeException ? runtimeException : new RuntimeException(e);
        }
    }

    private void runIfNotCanceled() {
        if (canceled.get()) {
            sendCancelled();
            finishCallback.accept(this);
            return;
        }
        runInternal();
    }

    private void runInternal() {
        ConsoleHelper.setHeaders(request.getConsoleMessage());
        restoreLocalHeaders();
        SqlExecutionLogConsumer logConsumer = null;
        try {
            Context context = request.getContext();
            if (context != null) {
                ContextUtils.setContext(context);
            }
            bindConnectionContext();
            sink.send("started", Map.of("executionId", request.getExecutionId()));
            DbDlExecuteRequest param = dbWebConverter.request2param(request.getSqlEditorRequest());
            logConsumer = new SqlExecutionLogConsumer(
                    new SqlExecutionConsumer(request, sink, dbWebConverter, largeValueTokenService,
                            executeResultEnhanceService, eventContext),
                    request,
                    sqlOperationLogRecorder);
            DbStreamingExecuteRequest executeStreamingRequest = new DbStreamingExecuteRequest();
            executeStreamingRequest.setExecutionId(request.getExecutionId());
            executeStreamingRequest.setDlExecuteRequest(param);
            executeStreamingRequest.setConsumer(logConsumer);
            executeStreamingRequest.setStatementListener(this);
            executeStreamingRequest.setCancellation(canceled::get);
            sqlExecutionService.executeStreaming(executeStreamingRequest);
            if (canceled.get()) {
                logConsumer.finishCancelled(request.getSqlEditorRequest().getSql(), null);
            } else {
                logConsumer.finishSuccess();
            }
            sink.send(canceled.get() ? "cancelled" : "finished", terminalEvent(null));
        } catch (Exception e) {
            if (canceled.get()) {
                recordTerminalStatus(logConsumer, SqlOperationLogStatusEnum.CANCELLED.getCode(), e.getMessage());
                sink.send("cancelled", terminalEvent(e.getMessage()));
            } else {
                log.error("SQL execution failed, executionId={}", request.getExecutionId(), e);
                recordTerminalStatus(logConsumer, SqlOperationLogStatusEnum.FAIL.getCode(), e.getMessage());
                sink.send("failed", terminalEvent(e.getMessage()));
            }
        } finally {
            currentStatement = null;
            ContextUtils.removeContext();
            connectionContextService.clear();
            finishCallback.accept(this);
        }
    }

    /**
     * Builds a terminal-event payload that always carries the executionId, an optional
     * message, and the console's current transaction state so the frontend can keep its
     * Commit/Rollback controls in sync after every execution (including cancellation, which
     * leaves an open transaction intact by design).
     */
    private Map<String, Object> terminalEvent(String message) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("executionId", request.getExecutionId());
        if (message != null) {
            payload.put("message", message);
        }
        Long consoleId = consoleId();
        payload.put("inTransaction", consoleId != null
                && connectionContextService.isInTransaction(consoleId));
        return payload;
    }

    private Long consoleId() {
        DbConnectionContextRequest connectionContext = request.getConnectionContext();
        if (connectionContext != null && connectionContext.getConsoleId() != null) {
            return connectionContext.getConsoleId();
        }
        return request.getSqlEditorRequest() == null ? null : request.getSqlEditorRequest().getConsoleId();
    }

    public void cancel() {
        canceled.set(true);
        Statement statement = currentStatement;
        if (statement != null) {
            try {
                statement.cancel();
            } catch (Exception e) {
                log.warn("cancel SQL statement failed, executionId={}", request.getExecutionId(), e);
            }
        }
        Future<?> localFuture = future;
        if (workerThread != null && localFuture != null) {
            localFuture.cancel(true);
        }
    }

    public boolean hasStarted() {
        return workerThread != null;
    }

    public void sendCancelled() {
        canceled.set(true);
        recordFallbackTerminalStatus(SqlOperationLogStatusEnum.CANCELLED.getCode(), null);
        sink.send("cancelled", terminalEvent(null));
    }

    public void pollMessages() {
        Statement statement = currentStatement;
        if (statement == null) {
            return;
        }
        try {
            List<Map<String, Object>> messages = collectWarnings(statement.getWarnings());
            synchronized (eventContext) {
                SqlExecutionEventIdentity identity = eventContext.currentIdentity();
                for (Map<String, Object> message : messages) {
                    sink.send("message", message, identity);
                }
            }
            statement.clearWarnings();
        } catch (Exception e) {
            log.debug("poll SQL messages failed, executionId={}", request.getExecutionId(), e);
        }
    }

    @Override
    public void onStatementCreated(Statement statement) {
        currentStatement = statement;
    }

    @Override
    public void onStatementClosed(Statement statement) {
        if (currentStatement == statement) {
            pollMessages();
            currentStatement = null;
        }
    }

    @Override
    public void onImplicitCommitWarning(String sql) {
        Map<String, Object> message = new HashMap<>();
        message.put("level", "WARN");
        message.put("message", I18nUtils.getMessage("transaction.implicitCommit.warning"));
        message.put("source", "transaction");
        synchronized (eventContext) {
            sink.send("message", message, eventContext.currentIdentity());
        }
    }

    private void recordTerminalStatus(SqlExecutionLogConsumer logConsumer, String status, String message) {
        if (logConsumer != null) {
            if (SqlOperationLogStatusEnum.CANCELLED.getCode().equals(status)) {
                logConsumer.finishCancelled(requestSql(), message);
            } else {
                logConsumer.finishFailed(requestSql(), message);
            }
            return;
        }
        recordFallbackTerminalStatus(status, message);
    }

    private void recordFallbackTerminalStatus(String status, String message) {
        sqlOperationLogRecorder.recordAsync(SqlOperationLogRecord.builder()
                .sql(requestSql())
                .status(status)
                .errorMessage(message)
                .executionId(request.getExecutionId())
                .source(SqlOperationLogSourceEnum.SQL_EDITOR_JCEF.name())
                .connectionProfile(request.getConnectionProfile())
                .context(request.getContext())
                .build());
    }

    private void bindConnectionContext() {
        DbConnectionContextRequest connectionContext = request.getConnectionContext();
        if (connectionContext == null) {
            return;
        }
        connectionContextService.bind(connectionContext);
        ConnectionProfile profile = connectionContextService.currentProfile();
        request.setConnectionProfile(profile);
    }

    private String requestSql() {
        return request.getSqlEditorRequest() == null ? null : request.getSqlEditorRequest().getSql();
    }

    private void restoreLocalHeaders() {
        Map<String, Object> headers = request.getHeaders();
        if (headers == null) {
            return;
        }
        headers.forEach((key, value) -> {
            if (key != null && value != null) {
                LocalCookie.setHeader(key, String.valueOf(value));
            }
        });
    }

    private List<Map<String, Object>> collectWarnings(SQLWarning warning) {
        List<Map<String, Object>> messages = new ArrayList<>();
        SQLWarning current = warning;
        while (current != null) {
            if (current.getMessage() != null && !current.getMessage().isBlank()) {
                Map<String, Object> item = new HashMap<>();
                item.put("level", "INFO");
                item.put("message", current.getMessage());
                item.put("errorCode", current.getErrorCode());
                item.put("sqlState", current.getSQLState());
                item.put("source", "statement-warning");
                messages.add(item);
            }
            current = current.getNextWarning();
        }
        return messages;
    }
}

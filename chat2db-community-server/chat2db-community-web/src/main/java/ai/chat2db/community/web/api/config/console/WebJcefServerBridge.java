package ai.chat2db.community.web.api.config.console;

import ai.chat2db.community.domain.api.model.request.runtime.DbConnectionContextRequest;
import ai.chat2db.community.tools.console.ConsoleMessage;
import ai.chat2db.community.tools.console.ConsoleResult;
import ai.chat2db.community.tools.console.bridge.IJcefServerBridge;
import ai.chat2db.community.tools.console.bridge.SqlExecuteResponse;
import ai.chat2db.community.tools.model.Context;
import ai.chat2db.community.tools.util.ContextUtils;
import ai.chat2db.community.web.api.adapter.db.execution.SqlExecutionCancelRequest;
import ai.chat2db.community.web.api.adapter.db.execution.SqlExecutionManager;
import ai.chat2db.community.web.api.adapter.db.execution.SqlExecutionRequest;
import ai.chat2db.community.web.api.adapter.db.execution.SqlExecutionStartResult;
import ai.chat2db.community.web.api.aspect.connection.ICustomConnection;
import ai.chat2db.community.web.api.model.http.CookieUtil;
import ai.chat2db.community.web.api.model.request.db.SqlEditorExecuteRequest;
import ai.chat2db.community.web.api.util.ApplicationContextUtil;
import com.alibaba.fastjson2.JSON;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.math3.util.Pair;

public class WebJcefServerBridge implements IJcefServerBridge {

    @Override
    public void setHeaders(ConsoleMessage message) {
        ConsoleHelper.setHeaders(message);
    }

    @Override
    public boolean isReady() {
        return ConsoleHelper.init;
    }

    @Override
    public ConsoleResult doController(ConsoleMessage message) {
        return ConsoleHelper.doController(message);
    }

    @Override
    public ConsoleResult error(Exception e, ConsoleMessage message) {
        return ConsoleHelper.error(e, message);
    }

    @Override
    public boolean loginToken(String token) {
        return false;
    }

    @Override
    public SqlExecuteResponse executeSql(ConsoleMessage message) {
        SqlEditorExecuteRequest sqlEditorRequest = JSON.parseObject(message.getMessage(), SqlEditorExecuteRequest.class);
        Context previousContext = ContextUtils.queryContext();
        Context context = resolveContext();
        try {
            ContextUtils.setContext(context);
            SqlExecutionManager manager = ApplicationContextUtil.getBean(SqlExecutionManager.class);
            SqlExecutionStartResult startResult = manager.start(SqlExecutionRequest.builder()
                    .requestUuid(message.getUuid())
                    .laneId(buildLaneId(sqlEditorRequest))
                    .sqlEditorRequest(sqlEditorRequest)
                    .consoleMessage(message)
                    .context(context)
                    .connectionContext(resolveConnectionContext(sqlEditorRequest))
                    .headers(message.getHeaders())
                    .build());
            return SqlExecuteResponse.builder()
                    .requestUuid(message.getUuid())
                    .executionId(startResult.getExecutionId())
                    .startResult(startResult)
                    .build();
        } finally {
            restoreContext(previousContext);
        }
    }

    @Override
    public boolean cancelSql(ConsoleMessage message) {
        SqlExecutionCancelRequest request = JSON.parseObject(message.getMessage(), SqlExecutionCancelRequest.class);
        SqlExecutionManager manager = ApplicationContextUtil.getBean(SqlExecutionManager.class);
        return manager.cancel(request.getExecutionId());
    }

    private Context resolveContext() {
        Context current = ContextUtils.queryContext();
        if (current != null) {
            return current;
        }
        Pair<String, String> pair = CookieUtil.getOrganizationInfo();
        Long organizationId = StringUtils.isNumeric(pair.getSecond()) ? Long.parseLong(pair.getSecond()) : null;
        return Context.builder()
                .organizationToken(pair.getFirst())
                .organizationId(organizationId)
                .build();
    }

    private String buildLaneId(SqlEditorExecuteRequest request) {
        if (request.getConsoleId() != null) {
            return "console:" + request.getConsoleId();
        }
        return "datasource:" + request.getDataSourceId() + ":" + StringUtils.defaultString(request.getDatabaseName())
                + ":" + StringUtils.defaultString(request.getSchemaName());
    }

    private DbConnectionContextRequest resolveConnectionContext(SqlEditorExecuteRequest request) {
        if (request.getDataSourceId() == null) {
            return null;
        }
        if (request.getDataSourceId() > 1L) {
            DbConnectionContextRequest param = new DbConnectionContextRequest();
            param.setDataSourceId(request.getDataSourceId());
            param.setDatabaseName(request.getDatabaseName());
            param.setConsoleId(request.getConsoleId());
            param.setSchemaName(request.getSchemaName());
            return param;
        }
        ICustomConnection customConnection = ApplicationContextUtil.getBean(ICustomConnection.class);
        return customConnection == null ? null : customConnection.getConnectionInfo(request.getDataSourceId(),
                request.getDatabaseName(), request.getSchemaName(), request.getConsoleId());
    }

    private void restoreContext(Context previousContext) {
        if (previousContext == null) {
            ContextUtils.removeContext();
            return;
        }
        ContextUtils.setContext(previousContext);
    }
}

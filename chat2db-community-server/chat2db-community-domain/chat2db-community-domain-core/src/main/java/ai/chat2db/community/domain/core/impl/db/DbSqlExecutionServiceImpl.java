package ai.chat2db.community.domain.core.impl.db;

import ai.chat2db.community.domain.api.model.sql.SqlExecuteRequest;
import ai.chat2db.community.domain.api.model.request.db.DbStreamingExecuteRequest;
import ai.chat2db.community.domain.api.model.request.db.DbDlExecuteRequest;
import ai.chat2db.community.domain.api.model.sql.extension.SqlExecutionContext;
import ai.chat2db.community.domain.api.model.sql.extension.SqlExecutionOperation;
import ai.chat2db.community.domain.api.model.sql.extension.SqlExecutionPlan;
import ai.chat2db.community.domain.api.service.db.IDbSqlCommandService;
import ai.chat2db.community.domain.api.service.db.IDbSqlExecutionService;
import ai.chat2db.community.domain.core.impl.db.extension.SqlExecutionPolicyManager;
import ai.chat2db.community.tools.util.I18nUtils;
import ai.chat2db.spi.ICommandExecutor;
import ai.chat2db.spi.model.datasource.ConnectInfo;
import ai.chat2db.spi.sql.Chat2DBContext;
import ai.chat2db.spi.DefaultSQLExecutor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.sql.SQLException;

@Service
public class DbSqlExecutionServiceImpl implements IDbSqlExecutionService {

    private final IDbSqlCommandService sqlSqlExecuteRequestService;
    private final SqlExecutionPolicyManager sqlExecutionPolicyManager;

    public DbSqlExecutionServiceImpl(IDbSqlCommandService sqlSqlExecuteRequestService,
            SqlExecutionPolicyManager sqlExecutionPolicyManager) {
        this.sqlSqlExecuteRequestService = sqlSqlExecuteRequestService;
        this.sqlExecutionPolicyManager = sqlExecutionPolicyManager;
    }

    @Override
    public void executeStreaming(DbStreamingExecuteRequest executeStreamingRequest) throws SQLException {
        DbDlExecuteRequest request = executeStreamingRequest.getDlExecuteRequest();
        if (StringUtils.isBlank(request.getSql())) {
            return;
        }
        ICommandExecutor executor = Chat2DBContext.getDbMetaData().getCommandExecutor();
        if (!(executor instanceof DefaultSQLExecutor sqlExecutor)) {
            throw new IllegalStateException(I18nUtils.getMessage("sqlExecution.streamingUnsupported"));
        }
        if (executeStreamingRequest.getCancellation() != null
                && executeStreamingRequest.getCancellation().isCanceled()) {
            throw new SQLException("SQL execution canceled");
        }
        SqlExecutionPlan executionPlan = sqlExecutionPolicyManager.plan(executionContext(request),
                executeStreamingRequest.getExecutionId());
        SqlExecuteRequest command = sqlSqlExecuteRequestService.toSqlExecuteRequest(request);
        command.setScript(executionPlan.getSql());
        sqlExecutionPolicyManager.applyMaxRows(command, executionPlan);
        sqlExecutionPolicyManager.beforeExecute(executionPlan);
        sqlExecutor.executeStreaming(command,
                sqlExecutionPolicyManager.wrapStreamingConsumer(executionPlan,
                        executeStreamingRequest.getConsumer()),
                executeStreamingRequest.getStatementListener(), executeStreamingRequest.getCancellation());
    }

    private SqlExecutionContext executionContext(DbDlExecuteRequest request) {
        ConnectInfo connectInfo = Chat2DBContext.getConnectInfo();
        return new SqlExecutionContext(
                connectInfo == null ? request.getDataSourceId() : connectInfo.getDataSourceId(),
                connectInfo == null ? null : connectInfo.getDbType(),
                request.getDatabaseName(), request.getSchemaName(), request.getTableName(), request.getSql(),
                SqlExecutionOperation.EXECUTE, null, request.getApplyId());
    }
}

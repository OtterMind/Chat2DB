package ai.chat2db.plugin.mysql.imports;

import ai.chat2db.community.domain.api.model.task.ImportTaskSpec;
import ai.chat2db.community.domain.api.service.db.ISqlBatchHandler;
import ai.chat2db.community.domain.api.service.task.TaskExecutionContext;
import ai.chat2db.spi.ISqlFileImportManager;

import java.sql.Connection;

public final class MysqlSqlFileImportManager implements ISqlFileImportManager {

    @Override
    public boolean supportsOptions(ImportTaskSpec spec) {
        return MysqlSqlFileOptionsHandler.supportsOptions(spec);
    }

    @Override
    public ISqlBatchHandler preflightHandler(ImportTaskSpec spec, TaskExecutionContext context,
                                             Connection connection) {
        return MysqlSqlFileOptionsHandler.mysqlPreflightHandler(spec, context, connection);
    }

    @Override
    public ISqlBatchHandler executionHandler(ImportTaskSpec spec, TaskExecutionContext context,
                                              int totalStatements) {
        return new MysqlSqlFileOptionsHandler(spec, context, totalStatements);
    }
}

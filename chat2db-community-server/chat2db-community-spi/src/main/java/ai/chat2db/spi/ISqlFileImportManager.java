package ai.chat2db.spi;

import ai.chat2db.community.domain.api.model.task.ImportTaskSpec;
import ai.chat2db.community.domain.api.service.db.ISqlBatchHandler;
import ai.chat2db.community.domain.api.service.task.TaskExecutionContext;

import java.sql.Connection;

/** Database-plugin policy for SQL-file execution options. */
public interface ISqlFileImportManager {

    boolean supportsOptions(ImportTaskSpec spec);

    ISqlBatchHandler preflightHandler(ImportTaskSpec spec, TaskExecutionContext context, Connection connection);

    ISqlBatchHandler executionHandler(ImportTaskSpec spec, TaskExecutionContext context, int totalStatements);
}

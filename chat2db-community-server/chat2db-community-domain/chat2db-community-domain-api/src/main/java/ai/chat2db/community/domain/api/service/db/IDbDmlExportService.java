package ai.chat2db.community.domain.api.service.db;

import ai.chat2db.community.domain.api.model.db.DbDmlExportPlan;
import ai.chat2db.community.domain.api.model.request.db.DbDmlExportRequest;

import java.io.IOException;
import java.io.OutputStream;
import java.util.function.LongConsumer;

/**
 * Exports relational DML data.
 */
public interface IDbDmlExportService {

    /**
     * Resolves a table name from SQL and optional scope names.
     *
     * @param sql SQL text to inspect.
     * @param databaseName database name that scopes the lookup.
     * @param schemaName schema name that scopes the lookup.
     * @return resolved table name, or null when it cannot be determined.
     */
    String resolveTableName(String sql, String databaseName, String schemaName);

    /**
     * Validates export input and resolves the concrete SQL, export type, and
     * target file name for an export request.
     *
     * @param dbDmlExportRequest export request.
     * @return export execution plan.
     */
    DbDmlExportPlan prepareExport(DbDmlExportRequest dbDmlExportRequest);

    /**
     * Exports DML data to an output stream.
     *
     * @param dbDmlExportRequest DML export parameters.
     * @param outputStream target output stream.
     * @param statementListener observes the active JDBC statement.
     * @param cancellationChecker checks whether the export was cancelled.
     * @param exportedRowsListener receives the number of newly exported rows.
     * @param fileFinalizationListener runs after the query is consumed and before the output writer is finalized.
     * @throws IOException when export content cannot be written.
     */
    void export(DbDmlExportRequest dbDmlExportRequest, OutputStream outputStream,
            ISqlExecutionStatementListener statementListener, Runnable cancellationChecker,
            LongConsumer exportedRowsListener, Runnable fileFinalizationListener) throws IOException;
}

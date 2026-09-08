package ai.chat2db.spi;

import ai.chat2db.community.domain.api.model.metadata.Procedure;
import ai.chat2db.community.domain.api.service.task.TaskExecutionContext;
import ai.chat2db.spi.model.datasource.ConnectInfo;
import ai.chat2db.spi.model.export.ExportCapability;
import ai.chat2db.spi.model.imports.ImportResourceSnapshot;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * Entry point for dialect-specific database management operations.
 */
public interface IDbManager {

    /**
     * Export abilities of this database. Parallel keyset export is opt-in so an unverified dialect
     * keeps the historical serial path until its owning plugin enables and tests the capability.
     */
    default ExportCapability getExportCapability() {
        return ExportCapability.SERIAL_ONLY;
    }

    /**
     * Starts a database-specific consistent snapshot for an export worker when supported.
     * The caller owns the connection and rolls the transaction back after the worker finishes.
     */
    default boolean startConsistentExportSnapshot(Connection connection) throws SQLException {
        return false;
    }

    /**
     * Captures dialect-owned resource and safety facts without mutating the database. Unknown is
     * explicit so the admission layer never mistakes a missing probe for a successful check.
     */
    default ImportResourceSnapshot probeImportResources(Connection connection, String databaseName,
            String schemaName) {
        return ImportResourceSnapshot.unknown("This database plugin does not provide import resource probes");
    }

    default Connection openConnection(ConnectInfo connectInfo) {
        return getConnection(connectInfo);
    }

    default void closeConnection(Connection connection) throws SQLException {
        if (connection != null) {
            connection.close();
        }
    }

    Connection getConnection(ConnectInfo connectInfo);

    void connectDatabase(Connection connection, String database);

    void modifyDatabase(Connection connection, String databaseName, String newDatabaseName);

    void createDatabase(Connection connection, String databaseName);

    void dropDatabase(Connection connection, String databaseName);

    void createSchema(Connection connection, String databaseName, String schemaName);

    void dropSchema(Connection connection, String databaseName, String schemaName);

    void modifySchema(Connection connection, String databaseName, String schemaName, String newSchemaName);

    String dropTable(Connection connection, String databaseName, String schemaName, String tableName);

    void dropFunction(Connection connection, String databaseName, String schemaName, String functionName);

    void dropTrigger(Connection connection, String databaseName, String schemaName, String triggerName);

    void dropProcedure(Connection connection, String databaseName, String schemaName, String procedureName);

    void updateProcedure(Connection connection, String databaseName, String schemaName, Procedure procedure)
            throws SQLException;

    void exportDatabase(Connection connection, String databaseName, String schemaName, boolean containData,
            TaskExecutionContext context) throws SQLException;

    void exportTable(Connection connection, String databaseName, String schemaName, String tableName,
            boolean containData, TaskExecutionContext context) throws SQLException;

    /** Builds truncate SQL from raw object names, quoting them inside the implementation. */
    String truncateTable(Connection connection, String databaseName, String schemaName, String tableName)
            throws SQLException;

    /**
     * Copies a table using raw object names; implementations quote identifiers when building SQL.
     */
    void copyTable(Connection connection, String databaseName, String schemaName, String tableName, String newTableName,
            boolean copyData) throws SQLException;

    void exportTableData(Connection connection, String databaseName, String schemaName, String tableName,
            TaskExecutionContext context) throws SQLException;

    void dropView(Connection connection, String databaseName, String schemaName, String viewName);
}

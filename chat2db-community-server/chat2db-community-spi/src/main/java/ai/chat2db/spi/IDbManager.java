package ai.chat2db.spi;

import ai.chat2db.community.domain.api.model.metadata.Procedure;
import ai.chat2db.community.domain.api.model.metadata.Tablespace;
import ai.chat2db.community.domain.api.service.task.TaskExecutionContext;
import ai.chat2db.spi.model.datasource.ConnectInfo;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * Entry point for dialect-specific database management operations.
 */
public interface IDbManager {

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

    /**
     * Creates an InnoDB General Tablespace. No-op (throws) for dialects that do not support
     * tablespaces. The data-file path is user-supplied and emitted verbatim; the application
     * never touches the filesystem.
     */
    default void createTablespace(Connection connection, Tablespace tablespace) {
        throw new UnsupportedOperationException("createTablespace not supported");
    }

    /**
     * Drops a tablespace. MySQL rejects a non-empty tablespace ({@code ER_TABLESPACE_NOT_EMPTY});
     * callers should surface occupying tables via the two-phase delete prepare step before calling.
     */
    default void dropTablespace(Connection connection, String tablespaceName) {
        throw new UnsupportedOperationException("dropTablespace not supported");
    }

    /**
     * Whether the current dialect/server supports writable InnoDB General Tablespace management.
     * MySQL supports create/drop/table placement/migration from 5.7.6 onward.
     */
    default boolean supportsTablespaceManagement() {
        return false;
    }

    /**
     * Renames a tablespace. MySQL 8.0+ only; dialects/versions that do not support rename should
     * throw {@link ai.chat2db.community.tools.exception.BusinessException} with key
     * {@code tablespace.rename.notSupported}.
     */
    default void alterTablespaceRename(Connection connection, String oldTablespaceName,
            String newTablespaceName) {
        throw new UnsupportedOperationException("alterTablespaceRename not supported");
    }

    /**
     * Whether the current dialect/server supports {@code ALTER TABLESPACE ... RENAME TO}
     * (MySQL 8.0+). Used to gate the rename action in the UI.
     */
    default boolean supportsTablespaceRename() {
        return false;
    }

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

    String truncateTable(Connection connection, String databaseName, String schemaName, String tableName)
            throws SQLException;

    void copyTable(Connection connection, String databaseName, String schemaName, String tableName, String newTableName,
            boolean copyData) throws SQLException;

    void exportTableData(Connection connection, String databaseName, String schemaName, String tableName,
            TaskExecutionContext context) throws SQLException;

    void dropView(Connection connection, String databaseName, String schemaName, String viewName);
}

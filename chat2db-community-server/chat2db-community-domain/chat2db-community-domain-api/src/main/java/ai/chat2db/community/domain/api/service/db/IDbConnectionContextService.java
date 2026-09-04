package ai.chat2db.community.domain.api.service.db;

import ai.chat2db.community.domain.api.config.DriverConfig;
import ai.chat2db.community.domain.api.model.metadata.ForeignKeyInfo;
import ai.chat2db.community.domain.api.model.metadata.Table;
import ai.chat2db.community.domain.api.model.runtime.ConnectionProfile;
import ai.chat2db.community.domain.api.model.runtime.TransactionStateResponse;
import ai.chat2db.community.domain.api.model.request.runtime.DbConnectionContextRequest;
import ai.chat2db.community.domain.api.model.request.runtime.McpConnectionContextRequest;
import ai.chat2db.community.domain.api.model.request.runtime.DbObjectsQueryRequest;

import java.util.List;
import java.util.concurrent.Callable;

/**
 * Manages connection context binding and current connection profile lookup.
 */
public interface IDbConnectionContextService {

    /**
     * Binds a connection context for the current execution scope.
     *
     * @param dbConnectionContextRequest connection context parameters to bind.
     */
    void bind(DbConnectionContextRequest dbConnectionContextRequest);

    /**
     * Builds a connection profile without binding it to the current thread.
     *
     * @param dbConnectionContextRequest connection context parameters.
     * @return resolved connection profile.
     */
    ConnectionProfile buildProfile(DbConnectionContextRequest dbConnectionContextRequest);

    /**
     * Binds a previously resolved connection profile to the current execution scope.
     *
     * @param profile connection profile.
     */
    void bindProfile(ConnectionProfile profile);

    /**
     * Binds an MCP connection context for the current execution scope.
     *
     * @param mcpConnectionContextRequest MCP connection context parameters to bind.
     */
    void bindMcp(McpConnectionContextRequest mcpConnectionContextRequest);

    /**
     * Clears the current connection context without closing external resources.
     */
    void clear();

    /**
     * Rebinds the current connection context to another database and clears cached connection state.
     *
     * @param databaseName target database name, or {@code null} for a server-level connection.
     */
    void rebindCurrentDatabase(String databaseName);

    /**
     * Closes the current connection context and releases runtime resources.
     */
    void close();

    /**
     * Begins a manual transaction for the console identified by the request: borrows one
     * isolated JDBC connection, switches it to auto-commit off, and binds it to the console
     * so subsequent executions in the same console reuse it.
     *
     * @param dbConnectionContextRequest connection context parameters (consoleId required).
     * @return resulting transaction state.
     */
    TransactionStateResponse beginManualTransaction(DbConnectionContextRequest dbConnectionContextRequest);

    /**
     * Commits the console's open transaction and releases the bound connection.
     *
     * @param dbConnectionContextRequest connection context parameters (consoleId required).
     * @return resulting transaction state, including the commit outcome.
     */
    TransactionStateResponse commitTransaction(DbConnectionContextRequest dbConnectionContextRequest);

    /**
     * Rolls back the console's open transaction and releases the bound connection.
     *
     * @param dbConnectionContextRequest connection context parameters (consoleId required).
     * @return resulting transaction state, including the rollback outcome.
     */
    TransactionStateResponse rollbackTransaction(DbConnectionContextRequest dbConnectionContextRequest);

    /**
     * Returns the current transaction state for the console.
     *
     * @param dbConnectionContextRequest connection context parameters (consoleId required).
     * @return current transaction state.
     */
    TransactionStateResponse getTransactionState(DbConnectionContextRequest dbConnectionContextRequest);

    /**
     * Releases the console's bound connection. Used when a console is closed or its
     * connection changes; rolls back any open transaction first.
     *
     * @param dbConnectionContextRequest connection context parameters (consoleId required).
     * @return resulting transaction state, including the release-time rollback outcome.
     */
    TransactionStateResponse releaseBoundConnection(DbConnectionContextRequest dbConnectionContextRequest);

    /**
     * Checks whether the console currently has an open (uncommitted) manual transaction.
     *
     * @param consoleId console identifier.
     * @return true when a manual transaction is open for the console.
     */
    boolean isInTransaction(Long consoleId);

    /**
     * Runs a unit of console-bound work under the same per-console lock used by manual
     * transaction commit, rollback, and release.
     *
     * @param consoleId console identifier.
     * @param action work to run.
     * @return action result.
     */
    <T> T withConsoleTransactionLock(Long consoleId, Callable<T> action) throws Exception;

    /**
     * Releases every console-bound transaction, rolling back any open ones. Intended for
     * application shutdown so no bound connection leaks.
     */
    void releaseAllBoundTransactions();

    /**
     * Returns the current connection profile bound to the execution scope.
     *
     * @return current connection profile.
     */
    ConnectionProfile currentProfile();

    /**
     * Returns a detached copy of the current connection profile.
     *
     * @return copied connection profile, or null if no profile is bound.
     */
    ConnectionProfile currentProfileSnapshot();

    /**
     * Returns the default JDBC driver configuration for a database type.
     *
     * @param dbType database type code used to select dialect-specific behavior.
     * @return default driver configuration for the database type.
     */
    DriverConfig getDefaultDriverConfig(String dbType);

    /**
     * Checks whether the current dialect can address multiple databases in one connection.
     *
     * @return true when cross-database metadata access is supported; false otherwise.
     */
    boolean supportCrossDatabase();

    /**
     * Checks whether the current dialect can address multiple schemas in one connection.
     *
     * @return true when cross-schema metadata access is supported; false otherwise.
     */
    boolean supportCrossSchema();

    /**
     * Checks whether the current dialect exposes database-level metadata.
     *
     * @return true when database names are meaningful for the current dialect; false otherwise.
     */
    boolean supportDatabase();

    /**
     * Checks whether the current dialect exposes schema-level metadata.
     *
     * @return true when schema names are meaningful for the current dialect; false otherwise.
     */
    boolean supportSchema();

    /**
     * Lists system database names for a database type.
     *
     * @param dbType database type code used to select dialect-specific behavior.
     * @return system database names, or an empty list when none are defined.
     */
    List<String> getSystemDatabases(String dbType);

    /**
     * Lists system schema names for a database type.
     *
     * @param dbType database type code used to select dialect-specific behavior.
     * @return system schema names, or an empty list when none are defined.
     */
    List<String> getSystemSchemas(String dbType);

    /**
     * Lists foreign keys imported by a table.
     *
     * @param databaseName database name that scopes the lookup.
     * @param schemaName schema name that scopes the lookup.
     * @param tableName table name whose imported keys are queried.
     * @return imported foreign-key metadata.
     */
    List<ForeignKeyInfo> getImportedKeys(String databaseName, String schemaName, String tableName);

    /**
     * Queries database objects visible in the supplied connection context.
     *
     * @param dbObjectsQueryRequest object lookup scope and filters.
     * @return matched table metadata.
     */
    List<Table> queryObjects(DbObjectsQueryRequest dbObjectsQueryRequest);
}

package ai.chat2db.community.domain.api.service.db;

import ai.chat2db.community.domain.api.model.PageResponse;
import ai.chat2db.community.domain.api.config.TableBuilderConfig;
import ai.chat2db.community.domain.api.model.metadata.*;
import ai.chat2db.community.domain.api.model.request.db.*;
import ai.chat2db.community.domain.api.model.result.ExecuteResponse;
import ai.chat2db.community.domain.api.model.sql.Sql;
import ai.chat2db.community.domain.api.service.task.TaskExecutionContext;

import java.util.List;

/**
 * Exposes relational table metadata, DDL, and generated SQL contracts.
 */
public interface IDbTableService {

    /**
     * Returns the create-table DDL for a table.
     *
     * @param dbTableShowCreateRequest create-table DDL query parameters.
     * @return generated SQL text.
     */
    String showCreateTable(DbTableShowCreateRequest dbTableShowCreateRequest);

    /**
     * Returns a create-table SQL example for a database type.
     *
     * @param dbType database type code used to select dialect-specific behavior.
     * @return generated SQL text.
     */
    String createTableExample(String dbType);

    /**
     * Returns an alter-table SQL example for a database type.
     *
     * @param dbType database type code used to select dialect-specific behavior.
     * @return generated SQL text.
     */
    String alterTableExample(String dbType);

    /**
     * Queries detailed table metadata with the requested selector.
     *
     * @param dbTableQueryRequest table query parameters.
     * @param selector metadata selector that controls which table details are loaded.
     * @return table metadata, or null when no matching table exists.
     */
    Table query(DbTableQueryRequest dbTableQueryRequest, TableSelector selector);

    /**
     * Builds SQL statements that transform one table definition into another.
     *
     * @param oldTable original table definition.
     * @param newTable target table definition.
     * @param tableBuilderConfig table SQL builder configuration.
     * @return generated SQL statements.
     */
    List<Sql> buildSql(Table oldTable, Table newTable, TableBuilderConfig tableBuilderConfig);

    /**
     * Queries table metadata with pagination and selector options.
     *
     * @param dbTablePageQueryRequest paged table query parameters.
     * @param selector metadata selector that controls which table details are loaded.
     * @return paged table metadata.
     */
    PageResponse<Table> pageQuery(DbTablePageQueryRequest dbTablePageQueryRequest, TableSelector selector);

    /**
     * Queries paged table metadata and applies user-specific pinned table ordering.
     *
     * @param dbTablePageQueryRequest paged table query parameters.
     * @param selector metadata selector that controls which table details are loaded.
     * @return paged table metadata with pinned state populated.
     */
    PageResponse<Table> pageQueryWithPinned(DbTablePageQueryRequest dbTablePageQueryRequest, TableSelector selector);

    /**
     * Lists simple table metadata for a datasource scope.
     *
     * @param dbTablePageQueryRequest paged table query parameters.
     * @return simple table metadata.
     */
    List<SimpleTable> queryTables(DbTablePageQueryRequest dbTablePageQueryRequest);

    /**
     * Lists columns for a table.
     *
     * @param dbTableQueryRequest table query parameters.
     * @return list of table column.
     */
    List<TableColumn> queryColumns(DbTableQueryRequest dbTableQueryRequest);

    /**
     * Lists indexes for a table.
     *
     * @param dbTableQueryRequest table query parameters.
     * @return list of table index.
     */
    List<TableIndex> queryIndexes(DbTableQueryRequest dbTableQueryRequest);

    /**
     * Lists key metadata for a table. Community defaults to index metadata when
     * the dialect does not expose a separate key contract.
     *
     * @param dbTableQueryRequest table query parameters.
     * @return list of table key metadata.
     */
    List<TableIndex> queryKeys(DbTableQueryRequest dbTableQueryRequest);

    /**
     * Lists supported column types for a datasource scope.
     *
     * @param dbTypeQueryRequest column type query parameters.
     * @return list of supported column types.
     */
    List<Type> queryTypes(DbTypeQueryRequest dbTypeQueryRequest);

    /**
     * Queries table metadata required by table-building flows.
     *
     * @param dbTypeQueryRequest column type query parameters.
     * @return table metadata for the requested scope.
     */
    TableMeta queryTableMeta(DbTypeQueryRequest dbTypeQueryRequest);

    /**
     * Builds DML SQL for copying table data.
     *
     * @param dbDmlSqlCopyRequest DML copy SQL parameters.
     * @return generated SQL text.
     */
    String copyDmlSql(DbDmlSqlCopyRequest dbDmlSqlCopyRequest);

    /**
     * Returns table DDL for AI-assisted view and table flows.
     *
     * @param dbTableDdlRequest table DDL query parameters.
     * @return generated SQL text.
     */
    String getTableDdl(DbTableDdlRequest dbTableDdlRequest);

    /**
     * Drops a table in the current connection scope.
     *
     * @param dbTableQueryRequest table query parameters.
     */
    void dropTable(DbTableQueryRequest dbTableQueryRequest);

    /**
     * Truncates a table in the current connection scope.
     *
     * @param dbTableQueryRequest table query parameters.
     */
    void truncateTable(DbTableQueryRequest dbTableQueryRequest);

    /**
     * Generates maintenance SQL for a table operation.
     *
     * @param dbTableQueryRequest table query parameters.
     * @param operationType maintenance operation: ANALYZE, OPTIMIZE, CHECK, or REPAIR.
     * @return generated SQL string.
     */
    String maintenanceSql(DbTableQueryRequest dbTableQueryRequest, String operationType);

    /**
     * Executes a table maintenance operation and returns the server result rows.
     *
     * @param dbTableQueryRequest table query parameters.
     * @param operationType maintenance operation: ANALYZE, OPTIMIZE, CHECK, or REPAIR.
     * @param taskContext task execution context for cancellation-aware statements.
     * @return execution results.
     */
    List<ExecuteResponse> executeMaintenance(DbTableQueryRequest dbTableQueryRequest, String operationType,
            TaskExecutionContext taskContext);

    /**
     * Copies a table in the current connection scope.
     *
     * @param dbTableCopyRequest table copy parameters.
     */
    void copyTable(DbTableCopyRequest dbTableCopyRequest);
}

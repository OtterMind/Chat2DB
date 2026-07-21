package ai.chat2db.community.domain.core.impl.ai;

import ai.chat2db.community.domain.api.enums.parser.SqlTypeEnum;
import ai.chat2db.community.domain.api.exception.ai.AiToolException;
import ai.chat2db.community.domain.api.exception.ai.AiToolInvalidArgumentException;
import ai.chat2db.community.domain.api.exception.ai.AiToolMetadataQueryException;
import ai.chat2db.community.domain.api.exception.ai.AiToolSqlConfirmationRequiredException;
import ai.chat2db.community.domain.api.exception.ai.AiToolSqlExecutionException;
import ai.chat2db.community.domain.api.model.request.db.DbDlExecuteRequest;
import ai.chat2db.community.domain.api.model.request.db.DbSchemaQueryRequest;
import ai.chat2db.community.domain.api.model.request.db.DbTablePageQueryRequest;
import ai.chat2db.community.domain.api.model.request.db.DbTableQueryRequest;
import ai.chat2db.community.domain.api.model.request.db.TableSelector;
import ai.chat2db.community.domain.api.model.request.db.DbTableShowCreateRequest;
import ai.chat2db.community.domain.api.model.request.datasource.DbDatabaseQueryAllRequest;
import ai.chat2db.community.domain.api.model.request.runtime.DbConnectionContextRequest;
import ai.chat2db.community.domain.api.model.PageResponse;
import ai.chat2db.community.domain.api.model.ai.TableSchemaResult;
import ai.chat2db.community.domain.api.model.runtime.ConnectionProfile;
import ai.chat2db.community.domain.api.service.db.IDbConnectionContextService;
import ai.chat2db.community.domain.api.service.db.IDbDatabaseService;
import ai.chat2db.community.domain.api.service.db.IDbDlTemplateService;
import ai.chat2db.community.domain.api.service.db.IDbSqlService;
import ai.chat2db.community.domain.api.service.db.IDbTableService;
import ai.chat2db.community.tools.wrapper.result.ListResult;
import ai.chat2db.community.tools.model.Context;
import ai.chat2db.community.tools.util.ContextUtils;
import ai.chat2db.community.domain.api.service.ops.IOpsSqlOperationLogService;
import ai.chat2db.community.domain.api.enums.operation.SqlOperationLogSourceEnum;
import ai.chat2db.community.domain.api.model.request.datasource.DbDataSourcePageQueryRequest;
import ai.chat2db.community.domain.api.model.request.operation.OpsSqlOperationLogListResultRequest;
import ai.chat2db.community.domain.api.service.storage.IWorkspaceStorageFacade;
import ai.chat2db.community.domain.api.model.storage.WorkspaceDataSource;
import ai.chat2db.community.domain.api.model.request.ai.AiToolContextRequest;
import ai.chat2db.community.domain.api.model.request.ai.AiExecuteSqlRequest;
import ai.chat2db.community.domain.api.model.request.ai.AiGetTablesSchemaRequest;
import ai.chat2db.community.domain.api.model.request.ai.AiListTablesRequest;
import ai.chat2db.community.domain.api.service.ai.IAiToolService;
import ai.chat2db.community.domain.api.model.metadata.Database;
import ai.chat2db.community.domain.api.model.result.ExecuteResponse;
import ai.chat2db.community.domain.api.model.metadata.Schema;
import ai.chat2db.community.domain.api.model.metadata.SimpleTable;
import ai.chat2db.community.domain.api.model.metadata.Table;
import ai.chat2db.community.domain.api.model.metadata.TableColumn;
import ai.chat2db.community.tools.exception.BusinessException;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Locale;
import java.util.stream.Collectors;

@Service
@Slf4j
public class AiToolServiceImpl implements IAiToolService {

    @Autowired
    private IDbTableService tableService;
    @Autowired
    private IDbDatabaseService databaseService;
    @Autowired
    private IDbDlTemplateService dlTemplateService;
    @Autowired
    private IOpsSqlOperationLogService sqlOperationLogRecorder;
    @Autowired
    private IDbConnectionContextService connectionContextService;
    @Autowired
    private IDbSqlService sqlService;
    @Autowired
    private IWorkspaceStorageFacade workspaceStorageFacade;
    // Execution page size bounds protect database work; AI preview truncation is handled by the web converter.
    private static final int DEFAULT_SQL_EXECUTION_PAGE_SIZE = 200;
    private static final int MAX_SQL_EXECUTION_PAGE_SIZE = 500;
    private static final int MAX_GLOBAL_DATASOURCES = 200;

    public List<WorkspaceDataSource> listAllDataSources(AiToolContextRequest toolContext) {
        DbDataSourcePageQueryRequest queryRequest = new DbDataSourcePageQueryRequest();
        queryRequest.setPageNo(1);
        queryRequest.setPageSize(MAX_GLOBAL_DATASOURCES);

        PageResponse<WorkspaceDataSource> result;
        try {
            result = invokeWithRequestContext(toolContext, () -> workspaceStorageFacade.listDataSources(queryRequest));
        } catch (BusinessException e) {
            throw new AiToolMetadataQueryException(
                    "Failed to query datasources: " + StringUtils.defaultString(e.getMessage(), "unknown error"),
                    e);
        }
        if (Objects.isNull(result)) {
            throw new AiToolMetadataQueryException("Failed to query datasources: unknown error");
        }
        return result.getData() == null ? Collections.emptyList() : result.getData().stream()
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    public List<SimpleTable> listAllTables(AiListTablesRequest aiListTablesRequest) {
        AiListTablesRequest request = requireRequest(aiListTablesRequest, "listAllTables request");
        Long dataSourceId = request.getDataSourceId();
        String databaseName = request.getDatabaseName();
        String schemaName = request.getSchemaName();
        AiToolContextRequest toolContext = request.getAiToolContextRequest();
        return invokeWithRequestContext(toolContext, () -> {
            ConnectionProfile profile = requireScopedConnectInfo(toolContext, dataSourceId, databaseName, schemaName);
            try {
                connectionContextService.bindProfile(profile);
                DbTablePageQueryRequest queryParam = DbTablePageQueryRequest.builder()
                        .dataSourceId(profile.getDataSourceId())
                        .databaseName(profile.getDatabaseName())
                        .schemaName(profile.getSchemaName())
                        .pageNo(1)
                        .pageSize(500)
                        .refresh(false)
                        .build();
                List<SimpleTable> result = tableService.queryTables(queryParam);
                return result == null ? Collections.emptyList() : result.stream()
                        .filter(Objects::nonNull)
                        .collect(Collectors.toList());
            } catch (AiToolException e) {
                throw e;
            } catch (BusinessException e) {
                throw new AiToolMetadataQueryException(
                        "Failed to query tables: " + StringUtils.defaultString(e.getMessage(), "unknown error"),
                        e);
            } finally {
                connectionContextService.clear();
            }
        });
    }

    public List<Database> listAllDatabases(Long dataSourceId,
            AiToolContextRequest toolContext) {
        return invokeWithRequestContext(toolContext, () -> {
            ConnectionProfile profile = requireScopedConnectInfo(toolContext, dataSourceId, null, null);
            try {
                connectionContextService.bindProfile(profile);
                DbDatabaseQueryAllRequest queryParam = DbDatabaseQueryAllRequest.builder()
                        .dataSourceId(profile.getDataSourceId())
                        .refresh(false)
                        .build();
                List<Database> result = databaseService.queryAll(queryParam);
                return result == null ? Collections.emptyList() : result.stream()
                        .filter(Objects::nonNull)
                        .collect(Collectors.toList());
            } catch (AiToolException e) {
                throw e;
            } catch (BusinessException e) {
                throw new AiToolMetadataQueryException(
                        "Failed to query databases: " + StringUtils.defaultString(e.getMessage(), "unknown error"),
                        e);
            } finally {
                connectionContextService.clear();
            }
        });
    }

    public List<Schema> listAllSchemas(String databaseName, Long dataSourceId,
            AiToolContextRequest toolContext) {
        return invokeWithRequestContext(toolContext, () -> {
            ConnectionProfile profile = requireScopedConnectInfo(toolContext, dataSourceId, databaseName, null);
            String targetDatabase = StringUtils.defaultIfBlank(databaseName, profile.getDatabaseName());
            if (StringUtils.isBlank(targetDatabase)) {
                throw new AiToolInvalidArgumentException("databaseName is required for listing schemas.");
            }
            try {
                connectionContextService.bindProfile(profile);
                DbSchemaQueryRequest queryParam = DbSchemaQueryRequest.builder()
                        .dataSourceId(profile.getDataSourceId())
                        .dataBaseName(targetDatabase)
                        .refresh(false)
                        .build();
                List<Schema> result = databaseService.querySchema(queryParam);
                return result == null ? Collections.emptyList() : result.stream()
                        .filter(Objects::nonNull)
                        .collect(Collectors.toList());
            } catch (AiToolException e) {
                throw e;
            } catch (BusinessException e) {
                throw new AiToolMetadataQueryException(
                        "Failed to query schemas: " + StringUtils.defaultString(e.getMessage(), "unknown error"),
                        e);
            } finally {
                connectionContextService.clear();
            }
        });
    }

    public List<ExecuteResponse> executeSql(AiExecuteSqlRequest aiExecuteSqlRequest) {
        AiExecuteSqlRequest request = requireRequest(aiExecuteSqlRequest, "executeSql request");
        String sql = request.getSql();
        Integer pageSize = request.getPageSize();
        Long dataSourceId = request.getDataSourceId();
        String databaseName = request.getDatabaseName();
        String schemaName = request.getSchemaName();
        AiToolContextRequest toolContext = request.getAiToolContextRequest();

        if (StringUtils.isBlank(sql)) {
            throw new AiToolInvalidArgumentException("sql is empty.");
        }
        String trimmedSql = sql.trim();
        return invokeWithRequestContext(toolContext, () -> {
            ConnectionProfile profile = requireScopedConnectInfo(toolContext, dataSourceId, databaseName, schemaName);
            int resolvedPageSize = normalizePageSize(pageSize);
            String unsafeSqlMessage = buildNonQueryExecutionMessage(trimmedSql, profile);
            if (StringUtils.isNotBlank(unsafeSqlMessage)) {
                throw new AiToolSqlConfirmationRequiredException(unsafeSqlMessage);
            }

            boolean operationLogged = false;
            try {
                connectionContextService.bindProfile(profile);
                DbDlExecuteRequest executeParam = new DbDlExecuteRequest();
                executeParam.setSql(trimmedSql);
                executeParam.setSingle(true);
                executeParam.setDataSourceId(profile.getDataSourceId());
                executeParam.setDatabaseName(profile.getDatabaseName());
                executeParam.setSchemaName(profile.getSchemaName());
                executeParam.setPageNo(1);
                executeParam.setPageSize(resolvedPageSize);
                executeParam.setPageSizeAll(false);
                executeParam.setErrorContinue(false);

                ListResult<ExecuteResponse> executeResult = wrapExecuteResults(dlTemplateService.execute(executeParam));
                OpsSqlOperationLogListResultRequest sqlOperationLogListResultRequest = OpsSqlOperationLogListResultRequest.of(
                        trimmedSql, executeResult.getSuccess(), executeResult.getErrorMessage(), executeResult.getData(),
                        SqlOperationLogSourceEnum.AI_TOOL.name());
                sqlOperationLogRecorder.recordListResultAsync(sqlOperationLogListResultRequest);
                operationLogged = true;
                if (Objects.isNull(executeResult) || !executeResult.success()) {
                    throw new AiToolSqlExecutionException("SQL execution failed: "
                            + (Objects.isNull(executeResult) ? "unknown error" : StringUtils.defaultString(executeResult.getErrorMessage())),
                            null);
                }
                return executeResult.getData() == null ? Collections.emptyList() : executeResult.getData();
            } catch (AiToolException e) {
                throw e;
            } catch (BusinessException e) {
                if (!operationLogged) {
                    sqlOperationLogRecorder.recordFailureAsync(trimmedSql, SqlOperationLogSourceEnum.AI_TOOL.name(), e.getMessage());
                }
                throw new AiToolSqlExecutionException(
                        "SQL execution failed: " + StringUtils.defaultString(e.getMessage(), "unknown error"),
                        e);
            } catch (RuntimeException e) {
                if (!operationLogged) {
                    sqlOperationLogRecorder.recordFailureAsync(trimmedSql, SqlOperationLogSourceEnum.AI_TOOL.name(), e.getMessage());
                }
                throw e;
            } finally {
                connectionContextService.clear();
            }
        });
    }

    public List<TableSchemaResult> getTablesSchema(AiGetTablesSchemaRequest aiGetTablesSchemaRequest) {
        AiGetTablesSchemaRequest request = requireRequest(aiGetTablesSchemaRequest, "getTablesSchema request");
        List<String> tableNames = request.getTableNames();
        Long dataSourceId = request.getDataSourceId();
        String databaseName = request.getDatabaseName();
        String schemaName = request.getSchemaName();
        AiToolContextRequest toolContext = request.getAiToolContextRequest();

        if (CollectionUtils.isEmpty(tableNames)) {
            throw new AiToolInvalidArgumentException("tableNames is empty.");
        }

        List<String> normalized = tableNames.stream()
                .filter(StringUtils::isNotBlank)
                .map(String::trim)
                .distinct()
                .limit(20)
                .collect(Collectors.toList());
        if (CollectionUtils.isEmpty(normalized)) {
            throw new AiToolInvalidArgumentException("tableNames is empty.");
        }

        return invokeWithRequestContext(toolContext, () -> {
            ConnectionProfile profile = requireScopedConnectInfo(toolContext, dataSourceId, databaseName, schemaName);
            try {
                connectionContextService.bindProfile(profile);
                List<TableSchemaResult> data = new ArrayList<>();
                for (String tableName : normalized) {
                    Table table = fetchDetailedTable(profile, tableName);
                    String ddl = fetchTableDdl(profile, tableName);
                    data.add(new TableSchemaResult(tableName, ddl, table));
                }
                return data;
            } catch (AiToolException e) {
                throw e;
            } catch (BusinessException e) {
                throw new AiToolMetadataQueryException(
                        "Failed to query table schema: " + StringUtils.defaultString(e.getMessage(), "unknown error"),
                        e);
            } finally {
                connectionContextService.clear();
            }
        });
    }

    private <T> T invokeWithRequestContext(AiToolContextRequest toolContext, java.util.function.Supplier<T> supplier) {
        Context currentContext = ContextUtils.queryThreadContext();
        Context requestContext = resolveRequestContext(toolContext);
        if (currentContext == null && requestContext != null) {
            ContextUtils.setContext(requestContext);
            try {
                return supplier.get();
            } finally {
                ContextUtils.removeContext();
            }
        }
        return supplier.get();
    }

    private Context resolveRequestContext(AiToolContextRequest toolContext) {
        if (toolContext == null) {
            return null;
        }
        return toolContext.getRequestContext();
    }

    private String fetchTableDdl(ConnectionProfile profile, String tableName) {
        DbTableShowCreateRequest showCreateTableParam = DbTableShowCreateRequest.builder()
                .dataSourceId(profile.getDataSourceId())
                .databaseName(profile.getDatabaseName())
                .schemaName(profile.getSchemaName())
                .tableName(tableName)
                .build();
        String ddlResult = tableService.showCreateTable(showCreateTableParam);
        if (StringUtils.isNotBlank(ddlResult)) {
            return ddlResult;
        }
        return fallbackSchema(profile, tableName);
    }

    private String fallbackSchema(ConnectionProfile profile, String tableName) {
        DbTableQueryRequest tableQueryParam = DbTableQueryRequest.builder()
                .dataSourceId(profile.getDataSourceId())
                .databaseName(profile.getDatabaseName())
                .schemaName(profile.getSchemaName())
                .tableName(tableName)
                .refresh(false)
                .build();
        TableSelector tableSelector = new TableSelector();
        tableSelector.setColumnList(true);
        tableSelector.setIndexList(false);
        Table tableResult = tableService.query(tableQueryParam, tableSelector);
        if (Objects.isNull(tableResult)) {
            return "-- DDL unavailable and table metadata query failed.";
        }
        List<TableColumn> columns = tableResult.getColumnList();
        if (CollectionUtils.isEmpty(columns)) {
            return "-- DDL unavailable and no column metadata found.";
        }
        List<String> lines = new ArrayList<>();
        lines.add("/* fallback schema */");
        lines.add("TABLE " + tableName + " (");
        for (TableColumn column : columns) {
            String nullable = Objects.equals(column.getNullable(), 0) ? "NOT NULL" : "NULL";
            String comment = StringUtils.isBlank(column.getComment()) ? "" : " -- " + column.getComment();
            lines.add("  " + column.getName() + " " + StringUtils.defaultIfBlank(column.getColumnType(), "UNKNOWN")
                    + " " + nullable + comment);
        }
        lines.add(");");
        return String.join("\n", lines);
    }

    private Table fetchDetailedTable(ConnectionProfile profile, String tableName) {
        DbTableQueryRequest tableQueryParam = DbTableQueryRequest.builder()
                .dataSourceId(profile.getDataSourceId())
                .databaseName(profile.getDatabaseName())
                .schemaName(profile.getSchemaName())
                .tableName(tableName)
                .refresh(false)
                .build();
        TableSelector selector = new TableSelector();
        selector.setColumnList(Boolean.TRUE);
        selector.setIndexList(Boolean.TRUE);
        Table table = tableService.query(tableQueryParam, selector);
        if (Objects.isNull(table)) {
            return null;
        }
        try {
            table.setForeignKeyList(connectionContextService.getImportedKeys(
                    profile.getDatabaseName(),
                    profile.getSchemaName(),
                    tableName));
        } catch (Exception e) { // impl-contract: fallback - foreign key hints are optional for AI schema rendering.
            log.debug("query foreign keys failed, tableName={}", tableName, e);
        }
        return table;
    }

    private ListResult<ExecuteResponse> wrapExecuteResults(List<ExecuteResponse> results) {
        ListResult<ExecuteResponse> result = ListResult.of(results);
        if (CollectionUtils.isEmpty(results)) {
            return result;
        }
        for (ExecuteResponse executeResult : results) {
            if (executeResult == null || Boolean.TRUE.equals(executeResult.getSuccess())) {
                continue;
            }
            result.setSuccess(false);
            result.errorCode(executeResult.getDescription());
            result.setErrorMessage(executeResult.getMessage());
            break;
        }
        return result;
    }

    private int normalizePageSize(Integer pageSize) {
        if (Objects.isNull(pageSize) || pageSize <= 0) {
            return DEFAULT_SQL_EXECUTION_PAGE_SIZE;
        }
        return Math.min(pageSize, MAX_SQL_EXECUTION_PAGE_SIZE);
    }

    private <T> T requireRequest(T request, String requestName) {
        if (request == null) {
            throw new AiToolInvalidArgumentException(requestName + " is required.");
        }
        return request;
    }

    private String buildNonQueryExecutionMessage(String sql, ConnectionProfile profile) {
        if (StringUtils.isBlank(sql)) {
            return null;
        }
        List<String> sqlTypes = resolveSqlTypes(sql, profile);
        if (CollectionUtils.isEmpty(sqlTypes)) {
            return null;
        }
        boolean queryOnly = sqlTypes.stream().allMatch(this::isSafeQuerySqlType);
        if (queryOnly) {
            return null;
        }
        String detectedTypes = String.join(", ", sqlTypes);
        return """
                Non-query SQL cannot be auto-executed by AI and requires manual confirmation.
                Detected SQL type(s): %s

                Return the SQL to the user for manual review:
                ```sql
                %s
                ```
                """.formatted(detectedTypes, sql);
    }

    private List<String> resolveSqlTypes(String sql, ConnectionProfile profile) {
        try {
            List<String> parsedTypes = sqlService.parseStatements(sql, profile.getDbType()).stream()
                    .map(statement -> normalizeSqlType(statement.getSqlType(), statement.getSql()))
                    .filter(StringUtils::isNotBlank)
                    .distinct()
                    .toList();
            if (CollectionUtils.isNotEmpty(parsedTypes)) {
                return parsedTypes;
            }
        } catch (Exception e) { // impl-contract: fallback - keyword matching is used when parser cannot classify SQL.
            log.debug("resolve sql type failed, fallback to keyword match", e);
        }
        String fallbackType = fallbackSqlType(sql);
        return StringUtils.isBlank(fallbackType) ? Collections.emptyList() : List.of(fallbackType);
    }

    private String normalizeSqlType(String sqlType, String sql) {
        if (StringUtils.isNotBlank(sqlType)) {
            return sqlType.trim().toUpperCase(Locale.ROOT);
        }
        return fallbackSqlType(sql);
    }

    private String fallbackSqlType(String sql) {
        if (StringUtils.isBlank(sql)) {
            return null;
        }
        String normalized = sql.stripLeading().toUpperCase(Locale.ROOT);
        if (normalized.startsWith("WITH")) {
            return SqlTypeEnum.SELECT.name();
        }
        if (normalized.startsWith("SELECT")) {
            return SqlTypeEnum.SELECT.name();
        }
        if (normalized.startsWith("SHOW")) {
            return SqlTypeEnum.SHOW_COLUMNS.name();
        }
        if (normalized.startsWith("DESC") || normalized.startsWith("DESCRIBE")) {
            return SqlTypeEnum.DESCRIBE.name();
        }
        if (normalized.startsWith("EXPLAIN")) {
            return SqlTypeEnum.OTHER.name();
        }
        return normalized.split("\\s+", 2)[0];
    }

    private boolean isSafeQuerySqlType(String sqlType) {
        if (StringUtils.isBlank(sqlType)) {
            return false;
        }
        return switch (sqlType) {
            case "SELECT", "SHOW_COLUMNS", "SHOW_CREATE_DB", "SHOW_CREATE_TABLE", "SHOW_CREATE_USER",
                    "SHOW_ERRORS", "SHOW_GRANTS", "SHOW_INDEXES", "SHOW_MASTER_LOGS", "SHOW_LOG_EVENTS",
                    "SHOW_OPEN_TABLES", "SHOW_PROFILE", "SHOW_SLAVE_STATUS", "DESCRIBE", "DESCRIBE_FULL" -> true;
            default -> false;
        };
    }

    private ConnectionProfile requireScopedConnectInfo(
            AiToolContextRequest toolContext,
            Long dataSourceId,
            String databaseName,
            String schemaName) {
        ConnectionProfile contextProfile = extractConnectionProfile(toolContext);
        Long resolvedDataSourceId = dataSourceId;
        String resolvedDatabaseName = databaseName;
        String resolvedSchemaName = schemaName;

        if (Objects.isNull(resolvedDataSourceId) && contextProfile != null) {
            resolvedDataSourceId = contextProfile.getDataSourceId();
        }
        if (StringUtils.isBlank(resolvedDatabaseName) && contextProfile != null) {
            resolvedDatabaseName = contextProfile.getDatabaseName();
        }
        if (StringUtils.isBlank(resolvedSchemaName) && contextProfile != null) {
            resolvedSchemaName = contextProfile.getSchemaName();
        }
        if (Objects.isNull(resolvedDataSourceId) && toolContext != null) {
            resolvedDataSourceId = toolContext.getDataSourceId();
        }
        if (StringUtils.isBlank(resolvedDatabaseName) && toolContext != null) {
            resolvedDatabaseName = toolContext.getDatabaseName();
        }
        if (StringUtils.isBlank(resolvedSchemaName) && toolContext != null) {
            resolvedSchemaName = toolContext.getSchemaName();
        }
        if (Objects.nonNull(resolvedDataSourceId)) {
            final Long scopedDataSourceId = resolvedDataSourceId;
            final String scopedDatabaseName = resolvedDatabaseName;
            final String scopedSchemaName = resolvedSchemaName;
            try {
                return invokeWithRequestContext(toolContext,
                        () -> buildProfile(scopedDataSourceId, scopedDatabaseName, scopedSchemaName));
            } catch (AiToolException e) {
                throw e;
            } catch (BusinessException e) {
                throw new AiToolMetadataQueryException(
                        "Failed to resolve database connection context: "
                                + StringUtils.defaultString(e.getMessage(), "unknown error"),
                        e);
            }
        }
        throw new AiToolInvalidArgumentException(
                "No database connection context found. Call list_all_datasources first, then provide dataSourceId/databaseName.");
    }

    private ConnectionProfile extractConnectionProfile(AiToolContextRequest toolContext) {
        if (Objects.isNull(toolContext)) {
            return null;
        }
        return toolContext.getConnectionProfile();
    }

    private ConnectionProfile buildProfile(Long dataSourceId, String databaseName, String schemaName) {
        DbConnectionContextRequest param = new DbConnectionContextRequest();
        param.setDataSourceId(dataSourceId);
        param.setDatabaseName(databaseName);
        param.setSchemaName(schemaName);
        return connectionContextService.buildProfile(param);
    }
}

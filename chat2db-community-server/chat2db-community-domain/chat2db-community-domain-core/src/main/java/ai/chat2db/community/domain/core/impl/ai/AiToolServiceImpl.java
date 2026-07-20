package ai.chat2db.community.domain.core.impl.ai;

import ai.chat2db.community.domain.api.enums.parser.SqlTypeEnum;
import ai.chat2db.community.domain.api.model.request.db.DbDlExecuteRequest;
import ai.chat2db.community.domain.api.model.request.db.DbSchemaQueryRequest;
import ai.chat2db.community.domain.api.model.request.db.DbTablePageQueryRequest;
import ai.chat2db.community.domain.api.model.request.db.DbTableQueryRequest;
import ai.chat2db.community.domain.api.model.request.db.TableSelector;
import ai.chat2db.community.domain.api.model.request.db.DbTableShowCreateRequest;
import ai.chat2db.community.domain.api.model.request.datasource.DbDatabaseQueryAllRequest;
import ai.chat2db.community.domain.api.model.request.runtime.DbConnectionContextRequest;
import ai.chat2db.community.domain.api.model.PageResponse;
import ai.chat2db.community.domain.api.model.ai.AiToolResult;
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
import ai.chat2db.community.domain.api.model.metadata.ForeignKeyInfo;
import ai.chat2db.community.domain.api.model.result.Header;
import ai.chat2db.community.domain.api.model.metadata.Schema;
import ai.chat2db.community.domain.api.model.metadata.SimpleTable;
import ai.chat2db.community.domain.api.model.metadata.Table;
import ai.chat2db.community.domain.api.model.metadata.TableColumn;
import ai.chat2db.community.domain.api.model.metadata.TableIndex;
import ai.chat2db.community.domain.api.model.metadata.TableIndexColumn;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONWriter;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    private static final int DEFAULT_SQL_PAGE_SIZE = 200;
    private static final int MAX_SQL_PAGE_SIZE = 500;
    private static final int MAX_SQL_RESULT_ROWS = 50;
    private static final int MAX_GLOBAL_DATASOURCES = 200;
    public String listAllDataSources(AiToolContextRequest toolContext) {
        DbDataSourcePageQueryRequest queryRequest = new DbDataSourcePageQueryRequest();
        queryRequest.setPageNo(1);
        queryRequest.setPageSize(MAX_GLOBAL_DATASOURCES);

        PageResponse<WorkspaceDataSource> result = invokeWithRequestContext(
                toolContext,
                () -> workspaceStorageFacade.listDataSources(queryRequest));
        if (Objects.isNull(result)) {
            return emitToolFailure(toolContext, "list_all_datasources",
                    "Failed to query datasources: unknown error", "DATASOURCE_QUERY_FAILED");
        }
        if (CollectionUtils.isEmpty(result.getData())) {
            return emitToolResult(toolContext, "list_all_datasources", "No datasources found.", Collections.emptyList());
        }

        List<Map<String, Object>> data = result.getData().stream()
                .filter(Objects::nonNull)
                .map(dataSource -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("id", dataSource.getId());
                    item.put("name", StringUtils.defaultIfBlank(dataSource.getAlias(), "(unnamed)"));
                    item.put("type", dataSource.getType());
                    item.put("env", dataSource.getEnvType());
                    return item;
                })
                .collect(Collectors.toList());
        return emitToolResult(toolContext, "list_all_datasources",
                "Found " + data.size() + " datasource(s).", data);
    }
    public String listAllTables(AiListTablesRequest aiListTablesRequest) {
        Long dataSourceId = aiListTablesRequest == null ? null : aiListTablesRequest.getDataSourceId();
        String databaseName = aiListTablesRequest == null ? null : aiListTablesRequest.getDatabaseName();
        String schemaName = aiListTablesRequest == null ? null : aiListTablesRequest.getSchemaName();
        AiToolContextRequest toolContext = aiListTablesRequest == null ? null : aiListTablesRequest.getAiToolContextRequest();
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
            if (CollectionUtils.isEmpty(result)) {
                return emitToolResult(toolContext, "list_all_tables", "No tables found.", Collections.emptyList());
            }
            List<Map<String, Object>> data = result.stream()
                    .filter(Objects::nonNull)
                    .map(this::tableSummaryData)
                    .collect(Collectors.toList());
            return emitToolResult(toolContext, "list_all_tables",
                    "Found " + data.size() + " table(s).", data);
        } finally {
            connectionContextService.clear();
        }
    }
    public String listAllDatabases(Long dataSourceId,
            AiToolContextRequest toolContext) {
        ConnectionProfile profile = requireScopedConnectInfo(toolContext, dataSourceId, null, null);
        try {
            connectionContextService.bindProfile(profile);
            DbDatabaseQueryAllRequest queryParam = DbDatabaseQueryAllRequest.builder()
                    .dataSourceId(profile.getDataSourceId())
                    .refresh(false)
                    .build();
            List<Database> result = databaseService.queryAll(queryParam);
            if (CollectionUtils.isEmpty(result)) {
                return emitToolResult(toolContext, "list_all_databases", "No databases found.", Collections.emptyList());
            }
            List<Map<String, Object>> data = result.stream()
                    .filter(Objects::nonNull)
                    .map(database -> {
                        Map<String, Object> item = new LinkedHashMap<>();
                        item.put("name", StringUtils.defaultString(database.getName(), "(unnamed)"));
                        item.put("system", database.isSystem());
                        item.put("comment", database.getComment());
                        return item;
                    })
                    .collect(Collectors.toList());
            return emitToolResult(toolContext, "list_all_databases",
                    "Found " + data.size() + " database(s).", data);
        } finally {
            connectionContextService.clear();
        }
    }
    public String listAllSchemas(String databaseName,Long dataSourceId,
            AiToolContextRequest toolContext) {
        ConnectionProfile profile = requireScopedConnectInfo(toolContext, dataSourceId, databaseName, null);
        String targetDatabase = StringUtils.defaultIfBlank(databaseName, profile.getDatabaseName());
        if (StringUtils.isBlank(targetDatabase)) {
            return emitToolFailure(toolContext, "list_all_schemas",
                    "databaseName is required for listing schemas.", "INVALID_ARGUMENT");
        }
        try {
            connectionContextService.bindProfile(profile);
            DbSchemaQueryRequest queryParam = DbSchemaQueryRequest.builder()
                    .dataSourceId(profile.getDataSourceId())
                    .dataBaseName(targetDatabase)
                    .refresh(false)
                    .build();
            List<Schema> result = databaseService.querySchema(queryParam);
            if (CollectionUtils.isEmpty(result)) {
                return emitToolResult(toolContext, "list_all_schemas", "No schemas found.", Collections.emptyList());
            }
            List<Map<String, Object>> data = result.stream()
                    .filter(Objects::nonNull)
                    .map(schema -> {
                        Map<String, Object> item = new LinkedHashMap<>();
                        item.put("name", StringUtils.defaultString(schema.getName(), "(unnamed)"));
                        item.put("system", schema.isSystem());
                        item.put("comment", schema.getComment());
                        return item;
                    })
                    .collect(Collectors.toList());
            return emitToolResult(toolContext, "list_all_schemas",
                    "Found " + data.size() + " schema(s).", data);
        } finally {
            connectionContextService.clear();
        }
    }
    public String executeSql(AiExecuteSqlRequest aiExecuteSqlRequest) {
        String sql = aiExecuteSqlRequest == null ? null : aiExecuteSqlRequest.getSql();
        Integer pageSize = aiExecuteSqlRequest == null ? null : aiExecuteSqlRequest.getPageSize();
        Long dataSourceId = aiExecuteSqlRequest == null ? null : aiExecuteSqlRequest.getDataSourceId();
        String databaseName = aiExecuteSqlRequest == null ? null : aiExecuteSqlRequest.getDatabaseName();
        String schemaName = aiExecuteSqlRequest == null ? null : aiExecuteSqlRequest.getSchemaName();
        AiToolContextRequest toolContext = aiExecuteSqlRequest == null ? null : aiExecuteSqlRequest.getAiToolContextRequest();

        if (StringUtils.isBlank(sql)) {
            return emitToolFailure(toolContext, "execute_sql", "sql is empty.", "INVALID_ARGUMENT");
        }
        ConnectionProfile profile = requireScopedConnectInfo(toolContext, dataSourceId, databaseName, schemaName);
        int resolvedPageSize = normalizePageSize(pageSize);
        String trimmedSql = sql.trim();
        String unsafeSqlMessage = buildNonQueryExecutionMessage(trimmedSql, profile);
        if (StringUtils.isNotBlank(unsafeSqlMessage)) {
            return emitToolFailure(toolContext, "execute_sql", unsafeSqlMessage, "SQL_REQUIRES_MANUAL_CONFIRMATION");
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
                return emitToolFailure(toolContext, "execute_sql", "SQL execution failed: "
                        + (Objects.isNull(executeResult) ? "unknown error" : StringUtils.defaultString(executeResult.getErrorMessage())),
                        "SQL_EXECUTION_FAILED");
            }
            if (CollectionUtils.isEmpty(executeResult.getData())) {
                return emitToolResult(toolContext, "execute_sql",
                        "SQL executed successfully with no result.", Collections.emptyList());
            }

            List<Map<String, Object>> data = new ArrayList<>();
            int index = 1;
            for (ExecuteResponse item : executeResult.getData()) {
                data.add(executeResponseData(index++, item));
            }
            return emitToolResult(toolContext, "execute_sql",
                    "SQL executed successfully with " + data.size() + " result set(s).", data);
        } catch (RuntimeException e) {
            if (!operationLogged) {
                sqlOperationLogRecorder.recordFailureAsync(trimmedSql, SqlOperationLogSourceEnum.AI_TOOL.name(), e.getMessage());
            }
            throw e;
        } finally {
            connectionContextService.clear();
        }
    }
    public String getTablesSchema(AiGetTablesSchemaRequest aiGetTablesSchemaRequest) {
        List<String> tableNames = aiGetTablesSchemaRequest == null ? null : aiGetTablesSchemaRequest.getTableNames();
        Long dataSourceId = aiGetTablesSchemaRequest == null ? null : aiGetTablesSchemaRequest.getDataSourceId();
        String databaseName = aiGetTablesSchemaRequest == null ? null : aiGetTablesSchemaRequest.getDatabaseName();
        String schemaName = aiGetTablesSchemaRequest == null ? null : aiGetTablesSchemaRequest.getSchemaName();
        AiToolContextRequest toolContext = aiGetTablesSchemaRequest == null ? null : aiGetTablesSchemaRequest.getAiToolContextRequest();

        if (CollectionUtils.isEmpty(tableNames)) {
            return emitToolFailure(toolContext, "get_tables_schema", "tableNames is empty.", "INVALID_ARGUMENT");
        }

        List<String> normalized = tableNames.stream()
                .filter(StringUtils::isNotBlank)
                .map(String::trim)
                .distinct()
                .limit(20)
                .collect(Collectors.toList());
        if (CollectionUtils.isEmpty(normalized)) {
            return emitToolFailure(toolContext, "get_tables_schema", "tableNames is empty.", "INVALID_ARGUMENT");
        }

        ConnectionProfile profile = requireScopedConnectInfo(toolContext, dataSourceId, databaseName, schemaName);
        try {
            connectionContextService.bindProfile(profile);
            List<Map<String, Object>> data = new ArrayList<>();
            for (String tableName : normalized) {
                Table table = fetchDetailedTable(profile, tableName);
                String ddl = fetchTableDdl(profile, tableName);
                String schemaText = buildRichTableSchema(tableName, ddl, table);
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("tableName", tableName);
                item.put("schema", schemaText);
                data.add(item);
            }
            return emitToolResult(toolContext, "get_tables_schema",
                    "Fetched schema for " + data.size() + " table(s).", data);
        } finally {
            connectionContextService.clear();
        }
    }

    private String emitToolResult(AiToolContextRequest toolContext, String toolName, String summary, List<?> data) {
        return successToolResultJson(toolName, summary, data);
    }

    private String emitToolFailure(AiToolContextRequest toolContext, String toolName, String summary, String errorCode) {
        return failureToolResultJson(toolName, summary, errorCode);
    }

    static String successToolResultJson(String toolName, String summary, List<?> data) {
        return JSON.toJSONString(AiToolResult.success(toolName, summary, data), JSONWriter.Feature.WriteNulls);
    }

    static String failureToolResultJson(String toolName, String summary, String errorCode) {
        return JSON.toJSONString(AiToolResult.failure(toolName, summary, errorCode), JSONWriter.Feature.WriteNulls);
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

    private Map<String, Object> tableSummaryData(SimpleTable table) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("name", StringUtils.defaultString(table.getName(), "(unnamed)"));
        item.put("type", StringUtils.defaultIfBlank(table.getTableType(), "TABLE"));
        item.put("comment", table.getComment());
        return item;
    }

    private String buildRichTableSchema(String tableName, String ddl, Table table) {

        StringBuilder builder = new StringBuilder(2048);
        builder.append("-- TABLE: ").append(tableName).append("\n");
        builder.append("/* physical schema */\n");
        builder.append(StringUtils.defaultIfBlank(ddl, "-- schema unavailable"));

        String primaryKeys = formatPrimaryKeys(table);
        if (StringUtils.isNotBlank(primaryKeys)) {
            builder.append("\n\n").append(primaryKeys);
        }

        String indexes = formatIndexes(table);
        if (StringUtils.isNotBlank(indexes)) {
            builder.append("\n\n").append(indexes);
        }

        String foreignKeys = formatForeignKeys(table);
        if (StringUtils.isNotBlank(foreignKeys)) {
            builder.append("\n\n").append(foreignKeys);
        }

        return builder.toString();
    }

    private String formatPrimaryKeys(Table table) {
        if (table == null || CollectionUtils.isEmpty(table.getColumnList())) {
            return null;
        }
        List<TableColumn> primaryKeys = table.getColumnList().stream()
                .filter(column -> Boolean.TRUE.equals(column.getPrimaryKey()))
                .sorted(Comparator.comparingInt(TableColumn::getPrimaryKeyOrder))
                .toList();
        if (CollectionUtils.isEmpty(primaryKeys)) {
            return null;
        }
        List<String> lines = new ArrayList<>();
        lines.add("/* primary keys */");
        lines.add(primaryKeys.stream()
                .map(TableColumn::getName)
                .collect(Collectors.joining(", ")));
        return String.join("\n", lines);
    }

    private String formatIndexes(Table table) {
        if (table == null || CollectionUtils.isEmpty(table.getIndexList())) {
            return null;
        }
        List<String> lines = new ArrayList<>();
        lines.add("/* indexes */");
        for (TableIndex index : table.getIndexList()) {
            List<TableIndexColumn> columns = index.getColumnList();
            String columnNames = CollectionUtils.isEmpty(columns)
                    ? ""
                    : columns.stream()
                    .sorted(Comparator.comparing(column -> Objects.requireNonNullElse(column.getOrdinalPosition(), (short) 0)))
                    .map(TableIndexColumn::getColumnName)
                    .filter(StringUtils::isNotBlank)
                    .collect(Collectors.joining(", "));
            List<String> parts = new ArrayList<>();
            parts.add("type=" + StringUtils.defaultIfBlank(index.getType(), "INDEX"));
            parts.add("unique=" + Boolean.TRUE.equals(index.getUnique()));
            if (StringUtils.isNotBlank(index.getMethod())) {
                parts.add("method=" + index.getMethod());
            }
            if (StringUtils.isNotBlank(index.getComment())) {
                parts.add("comment=" + index.getComment());
            }
            lines.add("- " + StringUtils.defaultIfBlank(index.getName(), "(unnamed)")
                    + (StringUtils.isNotBlank(columnNames) ? " (" + columnNames + ")" : "")
                    + " | " + String.join("; ", parts));
        }
        return lines.size() > 1 ? String.join("\n", lines) : null;
    }

    private String formatForeignKeys(Table table) {
        if (table == null || CollectionUtils.isEmpty(table.getForeignKeyList())) {
            return null;
        }
        Map<String, List<ForeignKeyInfo>> grouped = new LinkedHashMap<>();
        for (ForeignKeyInfo foreignKey : table.getForeignKeyList()) {
            String key = firstNonBlank(foreignKey.getFkName(),
                    foreignKey.getFkTableName() + "->" + foreignKey.getPkTableName());
            grouped.computeIfAbsent(key, ignored -> new ArrayList<>()).add(foreignKey);
        }

        List<String> lines = new ArrayList<>();
        lines.add("/* foreign keys */");
        for (Map.Entry<String, List<ForeignKeyInfo>> entry : grouped.entrySet()) {
            List<ForeignKeyInfo> fkList = entry.getValue().stream()
                    .sorted(Comparator.comparingInt(item -> item.getKeySeq()))
                    .toList();
            String fkColumns = fkList.stream()
                    .map(ForeignKeyInfo::getFkColumnName)
                    .filter(StringUtils::isNotBlank)
                    .collect(Collectors.joining(", "));
            String pkTable = fkList.stream()
                    .map(ForeignKeyInfo::getPkTableName)
                    .filter(StringUtils::isNotBlank)
                    .findFirst()
                    .orElse("(unknown)");
            String pkColumns = fkList.stream()
                    .map(ForeignKeyInfo::getPkColumnName)
                    .filter(StringUtils::isNotBlank)
                    .collect(Collectors.joining(", "));
            lines.add("- " + entry.getKey() + ": (" + fkColumns + ") -> " + pkTable + "(" + pkColumns + ")");
        }
        return lines.size() > 1 ? String.join("\n", lines) : null;
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (StringUtils.isNotBlank(value)) {
                return value;
            }
        }
        return null;
    }

    private String formatExecuteResponse(ExecuteResponse result) {
        if (Objects.isNull(result)) {
            return "Empty result.";
        }
        StringBuilder builder = new StringBuilder(1024);
        builder.append("success: ").append(Boolean.TRUE.equals(result.getSuccess())).append("\n");
        if (StringUtils.isNotBlank(result.getSqlType())) {
            builder.append("sqlType: ").append(result.getSqlType()).append("\n");
        }
        if (Objects.nonNull(result.getDuration())) {
            builder.append("durationMs: ").append(result.getDuration()).append("\n");
        }
        if (Objects.nonNull(result.getUpdateCount())) {
            builder.append("updateCount: ").append(result.getUpdateCount()).append("\n");
        }
        if (StringUtils.isNotBlank(result.getMessage())) {
            builder.append("message: ").append(result.getMessage()).append("\n");
        }
        if (StringUtils.isNotBlank(result.getDescription())) {
            builder.append("description: ").append(result.getDescription()).append("\n");
        }
        if (CollectionUtils.isNotEmpty(result.getHeaderList()) && CollectionUtils.isNotEmpty(result.getDataList())) {
            builder.append("rows: ").append(result.getDataList().size());
            if (Objects.nonNull(result.getHasNextPage())) {
                builder.append(", hasNextPage: ").append(result.getHasNextPage());
            }
            builder.append("\n");
            appendTabularPreview(builder, result.getHeaderList(), result.getDisplayDataList());
        }
        return builder.toString().trim();
    }

    private Map<String, Object> executeResponseData(int index, ExecuteResponse result) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("resultIndex", index);
        if (Objects.isNull(result)) {
            item.put("success", false);
            item.put("message", "Empty result.");
            item.put("text", "Empty result.");
            return item;
        }
        item.put("success", Boolean.TRUE.equals(result.getSuccess()));
        item.put("sqlType", result.getSqlType());
        item.put("durationMs", result.getDuration());
        item.put("updateCount", result.getUpdateCount());
        item.put("message", result.getMessage());
        item.put("description", result.getDescription());
        item.put("hasNextPage", result.getHasNextPage());
        item.put("rowCount", result.getDataList() == null ? 0 : result.getDataList().size());
        item.put("columns", columnNames(result.getHeaderList()));
        item.put("rows", rowPreview(result.getHeaderList(), result.getDisplayDataList()));
        item.put("text", formatExecuteResponse(result));
        return item;
    }

    private List<String> columnNames(List<Header> headers) {
        if (CollectionUtils.isEmpty(headers)) {
            return Collections.emptyList();
        }
        return headers.stream()
                .map(header -> StringUtils.defaultIfBlank(header.getName(), header.getColumnName()))
                .map(name -> StringUtils.defaultIfBlank(name, "col"))
                .collect(Collectors.toList());
    }

    private List<Map<String, Object>> rowPreview(List<Header> headers, List<List<String>> rows) {
        if (CollectionUtils.isEmpty(headers) || CollectionUtils.isEmpty(rows)) {
            return Collections.emptyList();
        }
        List<String> headerNames = columnNames(headers);
        int rowCount = Math.min(rows.size(), MAX_SQL_RESULT_ROWS);
        List<Map<String, Object>> result = new ArrayList<>(rowCount);
        for (int i = 0; i < rowCount; i++) {
            List<String> row = rows.get(i);
            Map<String, Object> rowData = new LinkedHashMap<>();
            for (int c = 0; c < headerNames.size(); c++) {
                String value = row != null && c < row.size() ? row.get(c) : null;
                rowData.put(headerNames.get(c), normalizeCell(value));
            }
            result.add(rowData);
        }
        return result;
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

    private void appendTabularPreview(StringBuilder builder, List<Header> headers, List<List<String>> rows) {
        List<String> headerNames = headers.stream()
                .map(header -> StringUtils.defaultIfBlank(header.getName(), header.getColumnName()))
                .map(name -> StringUtils.defaultIfBlank(name, "col"))
                .collect(Collectors.toList());
        builder.append(String.join("\t", headerNames)).append("\n");
        int rowCount = Math.min(rows.size(), MAX_SQL_RESULT_ROWS);
        for (int i = 0; i < rowCount; i++) {
            List<String> row = rows.get(i);
            List<String> normalized = new ArrayList<>(headerNames.size());
            for (int c = 0; c < headerNames.size(); c++) {
                String value = c < row.size() ? row.get(c) : "";
                normalized.add(normalizeCell(value));
            }
            builder.append(String.join("\t", normalized)).append("\n");
        }
        if (rows.size() > rowCount) {
            builder.append("... ").append(rows.size() - rowCount).append(" more rows not shown.");
        }
    }

    private String normalizeCell(String value) {
        if (value == null) {
            return "NULL";
        }
        String normalized = value.replace("\n", "\\n").replace("\r", "\\r").replace("\t", " ");
        if (normalized.length() > 200) {
            return normalized.substring(0, 197) + "...";
        }
        return normalized;
    }

    private int normalizePageSize(Integer pageSize) {
        if (Objects.isNull(pageSize) || pageSize <= 0) {
            return DEFAULT_SQL_PAGE_SIZE;
        }
        return Math.min(pageSize, MAX_SQL_PAGE_SIZE);
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
        ConnectionProfile contextProfile = resolveConnectionProfile(toolContext);
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
        if (Objects.nonNull(resolvedDataSourceId)) {
            final Long scopedDataSourceId = resolvedDataSourceId;
            final String scopedDatabaseName = resolvedDatabaseName;
            final String scopedSchemaName = resolvedSchemaName;
            return invokeWithRequestContext(toolContext,
                    () -> buildProfile(scopedDataSourceId, scopedDatabaseName, scopedSchemaName));
        }
        throw new IllegalArgumentException(
                "No database connection context found. Call list_all_datasources first, then provide dataSourceId/databaseName.");
    }

    private ConnectionProfile resolveConnectionProfile(AiToolContextRequest toolContext) {
        if (Objects.isNull(toolContext)) {
            return null;
        }
        if (toolContext.getConnectionProfile() != null) {
            return toolContext.getConnectionProfile();
        }
        Long dataSourceId = toolContext.getDataSourceId();
        if (Objects.isNull(dataSourceId)) {
            return null;
        }
        String databaseName = toolContext.getDatabaseName();
        String schemaName = toolContext.getSchemaName();
        return buildProfile(dataSourceId, databaseName, schemaName);
    }

    private ConnectionProfile requireConnectInfo(AiToolContextRequest toolContext) {
        ConnectionProfile profile = resolveConnectionProfile(toolContext);
        if (Objects.nonNull(profile)) {
            return profile;
        }
        throw new IllegalArgumentException("No database connection context found. Provide dataSourceId/databaseName.");
    }

    private ConnectionProfile buildProfile(Long dataSourceId, String databaseName, String schemaName) {
        DbConnectionContextRequest param = new DbConnectionContextRequest();
        param.setDataSourceId(dataSourceId);
        param.setDatabaseName(databaseName);
        param.setSchemaName(schemaName);
        return connectionContextService.buildProfile(param);
    }
}

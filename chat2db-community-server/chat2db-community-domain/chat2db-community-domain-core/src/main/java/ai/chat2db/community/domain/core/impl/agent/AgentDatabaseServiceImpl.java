package ai.chat2db.community.domain.core.impl.agent;

import ai.chat2db.community.domain.api.model.PageResponse;
import ai.chat2db.community.domain.api.model.agent.database.AgentDatabaseRequest;
import ai.chat2db.community.domain.api.model.agent.database.AgentDatabaseResult;
import ai.chat2db.community.domain.api.model.agent.database.AgentDatabaseException;
import ai.chat2db.community.domain.api.model.agent.database.AgentDatabaseResult.*;
import ai.chat2db.community.domain.api.model.metadata.Table;
import ai.chat2db.community.domain.api.model.request.datasource.DbDataSourcePageQueryRequest;
import ai.chat2db.community.domain.api.model.request.datasource.DbDatabaseQueryAllRequest;
import ai.chat2db.community.domain.api.model.request.db.*;
import ai.chat2db.community.domain.api.model.request.operation.OpsSqlOperationLogListResultRequest;
import ai.chat2db.community.domain.api.model.request.runtime.DbConnectionContextRequest;
import ai.chat2db.community.domain.api.model.result.ExecuteResponse;
import ai.chat2db.community.domain.api.model.runtime.ConnectionProfile;
import ai.chat2db.community.domain.api.service.agent.AgentDatabaseService;
import ai.chat2db.community.domain.api.service.db.*;
import ai.chat2db.community.domain.api.service.ops.IOpsSqlOperationLogService;
import ai.chat2db.community.domain.api.service.storage.IWorkspaceStorageFacade;
import ai.chat2db.community.domain.api.enums.operation.SqlOperationLogSourceEnum;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.function.Function;

@Service
public class AgentDatabaseServiceImpl implements AgentDatabaseService {
    private final IWorkspaceStorageFacade storage;
    private final IDbConnectionContextService connections;
    private final IDbDatabaseService databases;
    private final IDbTableService tables;
    private final IDbDlTemplateService executor;
    private final IDbSqlService sqlService;
    private final IOpsSqlOperationLogService audit;

    public AgentDatabaseServiceImpl(IWorkspaceStorageFacade storage, IDbConnectionContextService connections,
            IDbDatabaseService databases, IDbTableService tables, IDbDlTemplateService executor,
            IDbSqlService sqlService, IOpsSqlOperationLogService audit) {
        this.storage = storage;
        this.connections = connections;
        this.databases = databases;
        this.tables = tables;
        this.executor = executor;
        this.sqlService = sqlService;
        this.audit = audit;
    }

    @Override
    public AgentDatabaseResult<List<Source>> listSources(AgentDatabaseRequest.Sources request) {
        int page = page(request.page()), size = size(request.pageSize());
        var query = new DbDataSourcePageQueryRequest();
        query.setPageNo(page);
        query.setPageSize(size);
        query.setSearchKey(search(request.search()));
        var response = Objects.requireNonNull(storage.listDataSources(query), "Datasource lookup returned no response");
        var items = response.getData().stream().map(item -> new Source(String.valueOf(item.getId()),
                item.getAlias(), item.getType(), item.getEnvType())).toList();
        Page pagination = pageInfo(page, size, items.size(), response.getTotal(), response.getHasNextPage());
        return AgentDatabaseResult.success(null, items, pagination, Boolean.TRUE.equals(pagination.hasMore())
                ? next("db_list_datasources", nextPageArguments(request.search(), page + 1, size)) : null, List.of());
    }

    @Override
    public AgentDatabaseResult<Names> listDatabases(AgentDatabaseRequest.Databases request) {
        return scoped(new AgentDatabaseRequest.Scope(request.dataSourceId(), null, null), false, profile -> {
            int page = page(request.page()), size = size(request.pageSize());
            var items = databases.queryAll(DbDatabaseQueryAllRequest.builder().dataSourceId(profile.getDataSourceId())
                    .refresh(false).build()).stream().map(db -> new Name(db.getName(), db.getComment(), db.isSystem())).toList();
            return names(profile, items, page, size, "db_list_databases", Map.of("dataSourceId", request.dataSourceId()));
        });
    }

    @Override
    public AgentDatabaseResult<Names> listSchemas(AgentDatabaseRequest.Schemas request) {
        return scoped(new AgentDatabaseRequest.Scope(request.dataSourceId(), request.database(), null), false, profile -> {
            int page = page(request.page()), size = size(request.pageSize());
            requireDatabase(profile, request.database());
            var items = connections.supportSchema()
                    ? databases.querySchema(DbSchemaQueryRequest.builder().dataSourceId(profile.getDataSourceId())
                        .dataBaseName(profile.getDatabaseName()).refresh(false).build()).stream()
                        .map(schema -> new Name(schema.getName(), schema.getComment(), schema.isSystem())).toList()
                    : List.<Name>of();
            Map<String, Object> args = scopeArguments(profile); args.remove("schema");
            return names(profile, items, page, size, "db_list_schemas", args);
        });
    }

    @Override
    public AgentDatabaseResult<List<TableSummary>> listTables(AgentDatabaseRequest.Tables request) {
        return scoped(request.scope(), true, profile -> {
            int page = page(request.page()), size = size(request.pageSize());
            var query = DbTablePageQueryRequest.builder().dataSourceId(profile.getDataSourceId())
                    .databaseName(profile.getDatabaseName()).schemaName(profile.getSchemaName())
                    .searchKey(search(request.search())).pageNo(page).pageSize(size).refresh(false).build();
            PageResponse<Table> response = tables.pageQuery(query, TableSelector.builder().columnList(false).indexList(false).build());
            var items = response.getData().stream().map(table -> new TableSummary(table.getName(), table.getType(), table.getComment())).toList();
            var pagination = pageInfo(page, size, items.size(), response.getTotal(), response.getHasNextPage());
            Map<String, Object> args = scopeArguments(profile);
            args.putAll(nextPageArguments(request.search(), page + 1, size));
            return AgentDatabaseResult.success(scope(profile), items, pagination,
                    Boolean.TRUE.equals(pagination.hasMore()) ? next("db_list_tables", args) : null, List.of());
        });
    }

    @Override
    public AgentDatabaseResult<List<TableDetail>> describeTables(AgentDatabaseRequest.Describe request) {
        if (request.tables() == null || request.tables().isEmpty() || request.tables().size() > 10) {
            throw invalid("tables", "Provide 1 to 10 exact table names returned by db_list_tables.", null);
        }
        if (new HashSet<>(request.tables()).size() != request.tables().size()) {
            throw invalid("tables", "Table names must be unique.", null);
        }
        request.tables().forEach(name -> {
            required(name, "tables", null);
            if (name.length() > 256) throw invalid("tables", "Table names must not exceed 256 characters.", null);
        });
        return scoped(request.scope(), true, profile -> {
            var details = new ArrayList<TableDetail>();
            var warnings = new ArrayList<String>();
            for (String name : request.tables()) {
                var query = DbTableQueryRequest.builder().dataSourceId(profile.getDataSourceId())
                        .databaseName(profile.getDatabaseName()).schemaName(profile.getSchemaName()).tableName(name).refresh(false).build();
                Table table = tables.query(query, TableSelector.builder().columnList(true).indexList(true).build());
                if (table == null || table.getColumnList() == null || table.getColumnList().isEmpty()) {
                    throw new AgentDatabaseException("TABLE_NOT_FOUND", "tables", "Table metadata not found: " + name,
                            next("db_list_tables", scopeArguments(profile)));
                }
                var columns = table.getColumnList().stream().map(c -> new Column(c.getName(), c.getColumnType(),
                        c.getDataType(), c.getNullable() == null || c.getNullable() == 2 ? null : c.getNullable() == 1,
                        c.getDefaultValue(), c.getComment(), c.getPrimaryKey(), c.getGeneratedColumn())).toList();
                var indexes = table.getIndexList() == null ? List.<Index>of() : table.getIndexList().stream()
                        .map(index -> new Index(index.getName(), index.getUnique(), index.getColumnList() == null ? List.of()
                                : index.getColumnList().stream().map(column -> column.getColumnName()).toList())).toList();
                List<ForeignKey> foreignKeys = List.of();
                try {
                    foreignKeys = connections.getImportedKeys(profile.getDatabaseName(), profile.getSchemaName(), name).stream()
                            .map(fk -> new ForeignKey(fk.getFkName(), fk.getFkColumnName(), fk.getPkTableCat(), fk.getPkTableSchem(),
                                    fk.getPkTableName(), fk.getPkColumnName(), fk.getKeySeq())).toList();
                } catch (RuntimeException error) { // impl-contract: best-effort - foreign keys enrich otherwise complete column metadata.
                    warnings.add("Foreign keys unavailable for " + name); }
                String ddl = null;
                try {
                    ddl = tables.showCreateTable(DbTableShowCreateRequest.builder().dataSourceId(profile.getDataSourceId())
                            .databaseName(profile.getDatabaseName()).schemaName(profile.getSchemaName()).tableName(name).build());
                } catch (RuntimeException error) { // impl-contract: fallback - structured columns and indexes remain authoritative when DDL is unavailable.
                    warnings.add("DDL unavailable for " + name + "; use structured columns and indexes."); }
                details.add(new TableDetail(name, table.getComment(), columns, indexes, foreignKeys, ddl));
            }
            return AgentDatabaseResult.success(scope(profile), details, null, null, warnings);
        });
    }

    @Override
    public AgentDatabaseResult<QueryData> query(AgentDatabaseRequest.Query request) {
        required(request.sql(), "sql", null);
        if (request.sql().length() > 32768) throw invalid("sql", "SQL must not exceed 32768 characters.", null);
        int page = page(request.page()), size = size(request.pageSize());
        return scoped(request.scope(), true, profile -> {
            var statements = sqlService.parseStatements(request.sql(), profile.getDbType());
            if (statements.size() != 1 || !isQuery(statements.get(0).getSqlType())
                    || ("SELECT".equals(statements.get(0).getSqlType()) && !AgentSelectQueryPolicy.accepts(request.sql(), profile.getDbType()))) {
                throw new AgentDatabaseException("QUERY_REQUIRED", "sql", "db_query accepts one SELECT, SHOW or DESCRIBE statement. Writes, SELECT INTO, locking reads, unsupported SELECT syntax and multiple statements are not supported.", null);
            }
            var execute = new DbDlExecuteRequest();
            execute.setSql(request.sql());
            execute.setDataSourceId(profile.getDataSourceId());
            execute.setDatabaseName(profile.getDatabaseName());
            execute.setSchemaName(profile.getSchemaName());
            execute.setSingle(true);
            execute.setPageNo(page);
            execute.setPageSize(size);
            execute.setPageSizeAll(false);
            execute.setErrorContinue(false);
            List<ExecuteResponse> responses;
            try { responses = executor.execute(execute); }
            catch (RuntimeException failure) {
                audit.recordFailureAsync(request.sql(), SqlOperationLogSourceEnum.AI_TOOL.name(), failure.getMessage());
                throw new AgentDatabaseException("SQL_ERROR", "sql", failure.getMessage(), next("db_list_tables", scopeArguments(profile)), failure);
            }
            var failed = responses.stream().filter(item -> !Boolean.TRUE.equals(item.getSuccess())).findFirst();
            audit.recordListResultAsync(OpsSqlOperationLogListResultRequest.of(request.sql(), failed.isEmpty(),
                    failed.map(ExecuteResponse::getMessage).orElse(null), responses, SqlOperationLogSourceEnum.AI_TOOL.name()));
            if (failed.isPresent()) {
                throw new AgentDatabaseException("SQL_ERROR", "sql", failed.get().getMessage(),
                        next("db_list_tables", scopeArguments(profile)));
            }
            if (responses.size() != 1) throw new AgentDatabaseException("UNEXPECTED_RESULT", "sql", "Expected one query result set.", null);
            ExecuteResponse response = responses.get(0);
            var headers = response.getHeaderList() == null ? List.<ai.chat2db.community.domain.api.model.result.Header>of() : response.getHeaderList();
            var columnIndexes = java.util.stream.IntStream.range(0, headers.size())
                    .filter(i -> !ai.chat2db.community.domain.api.enums.plugin.DataTypeEnum.CHAT2DB_ROW_NUMBER.getCode().equals(headers.get(i).getDataType()))
                    .boxed().toList();
            var columns = columnIndexes.stream().map(i -> {
                var column = headers.get(i);
                return new QueryColumn(column.getName() == null ? column.getColumnName() : column.getName(),
                        column.getColumnType() == null ? column.getDataType() : column.getColumnType());
            }).toList();
            var rows = new ArrayList<List<String>>();
            var cellWarnings = new ArrayList<CellWarning>();
            if (response.getDataList() != null) {
                for (var sourceRow : response.getDataList()) {
                    if (sourceRow == null || sourceRow.size() != headers.size()) {
                        throw new AgentDatabaseException("UNEXPECTED_RESULT", null, "Result row does not match column metadata.", null);
                    }
                    var row = new ArrayList<String>();
                    for (int index : columnIndexes) {
                        var cell = sourceRow.get(index);
                        if (cell != null && (cell.isTruncated() || cell.getUnsupportedReason() != null)) {
                            cellWarnings.add(new CellWarning(rows.size(), row.size(), cell.getUnsupportedReason() == null
                                    ? "Value was truncated by the database result reader" : cell.getUnsupportedReason(), cell.getSizeChars(), cell.getLoadedChars()));
                        }
                        row.add(cell == null ? null : cell.getRawValue() instanceof String raw ? raw : cell.getValue());
                    }
                    rows.add(row);
                }
            }
            var pagination = pageInfo(page, size, rows.size(), null, response.getHasNextPage());
            Map<String, Object> args = scopeArguments(profile);
            args.put("sql", request.sql()); args.put("page", page + 1); args.put("pageSize", size);
            return AgentDatabaseResult.success(scope(profile), new QueryData(columns, rows, "database-text",
                    response.getExecutionMetrics() == null ? null : response.getExecutionMetrics().getTotalDurationMs(), cellWarnings),
                    pagination, Boolean.TRUE.equals(pagination.hasMore()) ? next("db_query", args) : null,
                    cellWarnings.isEmpty() ? List.of() : List.of("Some cells are incomplete; see data.cellWarnings (zero-based row and column)."));
        });
    }

    private <T> AgentDatabaseResult<T> scoped(AgentDatabaseRequest.Scope request, boolean requireScope,
            Function<ConnectionProfile, AgentDatabaseResult<T>> action) {
        required(request.dataSourceId(), "dataSourceId", next("db_list_datasources", Map.of()));
        long id;
        try { id = Long.parseLong(request.dataSourceId()); }
        catch (NumberFormatException error) { throw invalid("dataSourceId", "Copy the datasource id string from db_list_datasources.", next("db_list_datasources", Map.of())); }
        if (id <= 0) throw invalid("dataSourceId", "Datasource id must be a positive integer string.", next("db_list_datasources", Map.of()));
        for (String name : List.of("database", "schema")) {
            String value = name.equals("database") ? request.database() : request.schema();
            if (value != null && (value.isBlank() || value.length() > 256)) {
                throw invalid(name, name + " must be a nonempty identifier of at most 256 characters, or omitted.", null);
            }
        }
        var context = new DbConnectionContextRequest();
        context.setDataSourceId(id); context.setDatabaseName(request.database()); context.setSchemaName(request.schema());
        ConnectionProfile previous = connections.currentProfileSnapshot();
        try {
            ConnectionProfile profile = connections.buildProfile(context);
            connections.bindProfile(profile);
            if (requireScope) {
                requireDatabase(profile, request.database());
                if (connections.supportSchema() && blank(request.schema())) {
                    Map<String, Object> args = scopeArguments(profile); args.remove("schema");
                    throw invalid("schema", "Choose an exact schema name from db_list_schemas.", next("db_list_schemas", args));
                }
            }
            return action.apply(profile);
        } finally {
            connections.clear();
            if (previous != null) connections.bindProfile(previous);
        }
    }

    private void requireDatabase(ConnectionProfile profile, String requested) {
        if (connections.supportDatabase() && blank(requested)) {
            throw invalid("database", "Choose an exact database name from db_list_databases.",
                    next("db_list_databases", Map.of("dataSourceId", String.valueOf(profile.getDataSourceId()))));
        }
    }

    private AgentDatabaseResult<Names> names(ConnectionProfile profile, List<Name> items, int page, int size, String tool, Map<String, Object> args) {
        int start = Math.min((page - 1) * size, items.size()), end = Math.min(start + size, items.size());
        var pagination = pageInfo(page, size, end - start, (long) items.size(), end < items.size());
        var nextArgs = new LinkedHashMap<>(args); nextArgs.put("page", page + 1); nextArgs.put("pageSize", size);
        return AgentDatabaseResult.success(scope(profile), new Names(items.subList(start, end), connections.supportDatabase(), connections.supportSchema()),
                pagination, end < items.size() ? next(tool, nextArgs) : null, List.of());
    }

    private static boolean isQuery(String type) {
        return type != null && (type.equals("SELECT") || type.startsWith("SHOW_") || type.equals("DESCRIBE") || type.equals("DESCRIBE_FULL"));
    }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static void required(String value, String field, NextAction next) {
        if (blank(value)) throw new AgentDatabaseException(field.equals("dataSourceId") ? "MISSING_DATASOURCE" : "MISSING_ARGUMENT", field, field + " is required.", next);
    }
    private static int page(Integer value) {
        if (value != null && (value < 1 || value > 1000000)) throw invalid("page", "page must be between 1 and 1000000.", null);
        return value == null ? 1 : value;
    }
    private static int size(Integer value) {
        if (value != null && (value < 1 || value > 200)) throw invalid("pageSize", "pageSize must be between 1 and 200.", null);
        return value == null ? 50 : value;
    }
    private static String search(String value) {
        if (value != null && value.length() > 256) throw invalid("search", "search must not exceed 256 characters.", null);
        return value;
    }
    private static Page pageInfo(int page, int size, int returned, Long total, Boolean more) {
        return new Page(page, size, returned, total, more, Boolean.TRUE.equals(more) ? page + 1 : null);
    }
    private static Scope scope(ConnectionProfile profile) {
        return new Scope(String.valueOf(profile.getDataSourceId()), profile.getDbType(), profile.getDatabaseName(), profile.getSchemaName());
    }
    private static Map<String, Object> scopeArguments(ConnectionProfile profile) {
        var args = new LinkedHashMap<String, Object>(); args.put("dataSourceId", String.valueOf(profile.getDataSourceId()));
        if (!blank(profile.getDatabaseName())) args.put("database", profile.getDatabaseName());
        if (!blank(profile.getSchemaName())) args.put("schema", profile.getSchemaName());
        return args;
    }
    private static Map<String, Object> nextPageArguments(String search, int page, int size) {
        var args = new LinkedHashMap<String, Object>(); args.put("page", page); args.put("pageSize", size);
        if (!blank(search)) args.put("search", search);
        return args;
    }
    private static NextAction next(String tool, Map<String, Object> arguments) { return new NextAction(tool, arguments); }
    private static AgentDatabaseException invalid(String field, String message, NextAction next) { return new AgentDatabaseException("INVALID_ARGUMENT", field, message, next); }
}

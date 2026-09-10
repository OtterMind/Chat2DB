package ai.chat2db.community.domain.core.impl.agent;

import ai.chat2db.community.domain.api.constant.agent.AgentDatabaseConstant;
import ai.chat2db.community.domain.api.enums.operation.SqlOperationLogSourceEnum;
import ai.chat2db.community.domain.api.model.metadata.Table;
import ai.chat2db.community.domain.api.model.request.agent.DbAgentDatabaseRequest;
import ai.chat2db.community.domain.api.model.request.datasource.DbDataSourcePageQueryRequest;
import ai.chat2db.community.domain.api.model.request.db.*;
import ai.chat2db.community.domain.api.model.request.operation.OpsSqlOperationLogListResultRequest;
import ai.chat2db.community.domain.api.model.request.runtime.DbConnectionContextRequest;
import ai.chat2db.community.domain.api.model.response.agent.DbAgentDatabaseResponse.*;
import ai.chat2db.community.domain.api.model.response.agent.DbAgentDatabaseResponse;
import ai.chat2db.community.domain.api.model.result.ExecuteResponse;
import ai.chat2db.community.domain.api.model.runtime.ConnectionProfile;
import ai.chat2db.community.domain.api.model.storage.WorkspaceDataSource;
import ai.chat2db.community.domain.api.service.agent.AgentDatabaseService;
import ai.chat2db.community.domain.api.service.agent.AgentMetadataService;
import ai.chat2db.community.domain.api.service.db.*;
import ai.chat2db.community.domain.api.service.ops.IOpsSqlOperationLogService;
import ai.chat2db.community.domain.api.service.storage.IWorkspaceStorageFacade;
import ai.chat2db.community.tools.exception.agent.AgentDatabaseException;
import ai.chat2db.community.tools.model.agent.tool.AgentToolNextAction;
import java.util.*;
import java.util.function.Function;
import org.springframework.stereotype.Service;

@Service
public class AgentDatabaseServiceImpl implements AgentDatabaseService {
    private final IWorkspaceStorageFacade storage;
    private final IDbConnectionContextService connections;
    private final AgentMetadataService metadata;
    private final IDbDlTemplateService executor;
    private final IDbSqlService sqlService;
    private final IOpsSqlOperationLogService audit;

    public AgentDatabaseServiceImpl(IWorkspaceStorageFacade storage, IDbConnectionContextService connections,
            AgentMetadataService metadata, IDbDlTemplateService executor,
            IDbSqlService sqlService, IOpsSqlOperationLogService audit) {
        this.storage = storage;
        this.connections = connections;
        this.metadata = metadata;
        this.executor = executor;
        this.sqlService = sqlService;
        this.audit = audit;
    }

    @Override
    public DbAgentDatabaseResponse<List<Source>> listSources(DbAgentDatabaseRequest.Sources request) {
        int page = page(request.page()), size = size(request.pageSize());
        String search = search(request.search());
        if (!blank(search)) {
            var items = matchingSources(search);
            return metadataPage(null, items, page, size, "db_search_datasources", new LinkedHashMap<>(Map.of("search", search)));
        }
        var query = new DbDataSourcePageQueryRequest();
        query.setPageNo(page);
        query.setPageSize(size);
        var response = Objects.requireNonNull(storage.listDataSources(query), "Datasource lookup returned no response");
        var items = response.getData().stream().map(AgentDatabaseServiceImpl::source).toList();
        Page pagination = pageInfo(page, size, items.size(), response.getTotal(), response.getHasNextPage());
        return DbAgentDatabaseResponse.success(null, items, pagination, Boolean.TRUE.equals(pagination.hasMore())
                ? next("db_search_datasources", nextPageArguments(request.search(), page + 1, size)) : null, List.of());
    }

    @Override
    public DbAgentDatabaseResponse<Names> listDatabases(DbAgentDatabaseRequest.Databases request) {
        return scoped(new DbAgentDatabaseRequest.Scope(request.dataSourceId(), null, null), false, profile -> {
            int page = page(request.page()), size = size(request.pageSize());
            String pattern = AgentMetadataPattern.validate(request.databasePattern(), "databasePattern");
            var items = metadata.databases(pattern, Boolean.TRUE.equals(request.refresh())).stream()
                    .map(db -> new Name(db.getName(), db.getComment(), db.isSystem())).toList();
            Map<String, Object> args = new LinkedHashMap<>(Map.of("dataSourceId", request.dataSourceId()));
            put(args, "databasePattern", pattern);
            return names(profile, items, page, size, "db_search_databases", args);
        });
    }

    @Override
    public DbAgentDatabaseResponse<Names> listSchemas(DbAgentDatabaseRequest.Schemas request) {
        return scoped(new DbAgentDatabaseRequest.Scope(request.dataSourceId(), request.database(), null), false, profile -> {
            int page = page(request.page()), size = size(request.pageSize());
            requireDatabase(profile, request.database());
            String pattern = AgentMetadataPattern.validate(request.schemaPattern(), "schemaPattern");
            var items = connections.supportSchema()
                    ? metadata.schemas(profile.getDatabaseName(), pattern, Boolean.TRUE.equals(request.refresh())).stream()
                        .map(schema -> new Name(schema.getName(), schema.getComment(), schema.isSystem())).toList()
                    : List.<Name>of();
            Map<String, Object> args = scopeArguments(profile); args.remove("schema");
            put(args, "schemaPattern", pattern);
            return names(profile, items, page, size, "db_search_schemas", args);
        });
    }

    @Override
    public DbAgentDatabaseResponse<List<TableSummary>> listTables(DbAgentDatabaseRequest.Tables request) {
        int page = page(request.page()), size = size(request.pageSize());
        return scoped(request.scope(), false, profile -> {
            requireDatabase(profile, request.database());
            String schemaPattern = metadataSchema(request.schema(), request.schemaPattern());
            String tablePattern = AgentMetadataPattern.validate(request.tablePattern(), "tablePattern");
            String search = search(request.search());
            if (tablePattern != null && search != null) throw invalid("search", "Use tablePattern or search, not both.", null);
            if (search != null) tablePattern = "%" + AgentMetadataPattern.literal(search) + "%";
            var items = metadata.tables(request.database(), schemaPattern, tablePattern, Boolean.TRUE.equals(request.refresh())).stream()
                    .map(table -> new TableSummary(table.getName(), table.getType(), table.getComment(), table.getDatabaseName(), table.getSchemaName()))
                    .sorted(Comparator.comparing(TableSummary::database, Comparator.nullsFirst(String::compareTo))
                            .thenComparing(TableSummary::schema, Comparator.nullsFirst(String::compareTo)).thenComparing(TableSummary::name)).toList();
            Map<String, Object> args = metadataArguments(request.dataSourceId(), request.database(), request.schema(), request.schemaPattern());
            put(args, "search", search); put(args, "tablePattern", request.tablePattern());
            return metadataPage(metadataScope(profile, request.schema()), items, page, size, "db_search_tables", args);
        });
    }

    @Override
    public DbAgentDatabaseResponse<List<ColumnSummary>> listColumns(DbAgentDatabaseRequest.Columns request) {
        int page = page(request.page()), size = size(request.pageSize());
        return scoped(request.scope(), false, profile -> {
            requireDatabase(profile, request.database());
            String schemaPattern = metadataSchema(request.schema(), request.schemaPattern());
            String tablePattern = AgentMetadataPattern.validate(request.tablePattern(), "tablePattern");
            String columnPattern = AgentMetadataPattern.validate(request.columnPattern(), "columnPattern");
            var items = metadata.columns(request.database(), schemaPattern, tablePattern, columnPattern, Boolean.TRUE.equals(request.refresh())).stream()
                    .map(c -> new ColumnSummary(c.getDatabaseName(), c.getSchemaName(), c.getTableName(), c.getName(), c.getColumnType(),
                            c.getDataType(), c.getNullable() == null || c.getNullable() == 2 ? null : c.getNullable() == 1,
                            c.getDefaultValue(), c.getComment(), c.getOrdinalPosition()))
                    .sorted(Comparator.comparing(ColumnSummary::database, Comparator.nullsFirst(String::compareTo))
                            .thenComparing(ColumnSummary::schema, Comparator.nullsFirst(String::compareTo)).thenComparing(ColumnSummary::table)
                            .thenComparing(ColumnSummary::ordinalPosition, Comparator.nullsFirst(Integer::compareTo)).thenComparing(ColumnSummary::name)).toList();
            Map<String, Object> args = metadataArguments(request.dataSourceId(), request.database(), request.schema(), request.schemaPattern());
            put(args, "tablePattern", tablePattern); put(args, "columnPattern", columnPattern);
            return metadataPage(metadataScope(profile, request.schema()), items, page, size, "db_search_columns", args);
        });
    }

    @Override
    public DbAgentDatabaseResponse<List<ObjectDetail>> describeObjects(DbAgentDatabaseRequest.Describe request) {
        if (request.objects() == null || request.objects().isEmpty() || request.objects().size() > 10) {
            throw invalid("objects", "Provide 1 to 10 objects with an exact name and type.", null);
        }
        if (new HashSet<>(request.objects()).size() != request.objects().size()) {
            throw invalid("objects", "Each object type/name pair must be unique.", null);
        }
        for (var object : request.objects()) {
            if (object == null || object.type() == null || !AgentDatabaseConstant.OBJECT_TYPES.contains(object.type())) {
                throw invalid("objects", "Object type must be one of: " + String.join(", ", AgentDatabaseConstant.OBJECT_TYPES), null);
            }
            required(object.name(), "objects", null);
            if (object.name().length() > 256) throw invalid("objects", "Object names must not exceed 256 characters.", null);
        }
        return scoped(request.scope(), true, profile -> {
            var details = new ArrayList<ObjectDetail>();
            var warnings = new ArrayList<String>();
            for (var object : request.objects()) {
                AgentMetadataService.Description description;
                try {
                    description = metadata.describe(profile.getDatabaseName(), profile.getSchemaName(), object.type(), object.name(), Boolean.TRUE.equals(request.refresh()));
                } catch (AgentDatabaseException error) {
                    if (error.nextAction() == null && ("OBJECT_NOT_FOUND".equals(error.code()) || "OBJECT_TYPE_MISMATCH".equals(error.code()))) {
                        var args = scopeArguments(profile); args.put("tablePattern", AgentMetadataPattern.literal(object.name()));
                        throw new AgentDatabaseException(error.code(), error.field(), error.getMessage(), next("db_search_tables", args), error);
                    }
                    throw error;
                }
                Table table = description.table();
                warnings.addAll(description.warnings());
                var columns = table == null ? null : table.getColumnList().stream().map(c -> new Column(c.getName(), c.getColumnType(),
                        c.getDataType(), c.getNullable() == null || c.getNullable() == 2 ? null : c.getNullable() == 1,
                        c.getDefaultValue(), c.getComment(), c.getPrimaryKey(), c.getGeneratedColumn())).toList();
                var indexes = table == null ? null : table.getIndexList().stream()
                        .map(index -> new Index(index.getName(), index.getUnique(), index.getColumnList() == null ? List.of()
                                : index.getColumnList().stream().map(column -> column.getColumnName()).toList())).toList();
                var foreignKeys = table == null ? null : table.getForeignKeyList().stream()
                        .map(fk -> new ForeignKey(fk.getFkName(), fk.getFkColumnName(), fk.getPkTableCat(), fk.getPkTableSchem(),
                                fk.getPkTableName(), fk.getPkColumnName(), fk.getKeySeq())).toList();
                details.add(new ObjectDetail(object.name(), object.type(), table == null ? null : table.getComment(),
                        columns, indexes, foreignKeys, description.definition()));
            }
            return DbAgentDatabaseResponse.success(scope(profile), details, null, null, warnings);
        });
    }

    @Override
    public DbAgentDatabaseResponse<QueryData> query(DbAgentDatabaseRequest.Query request) {
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
                throw new AgentDatabaseException("SQL_ERROR", "sql", failure.getMessage(), next("db_search_tables", scopeArguments(profile)), failure);
            }
            var failed = responses.stream().filter(item -> !Boolean.TRUE.equals(item.getSuccess())).findFirst();
            audit.recordListResultAsync(OpsSqlOperationLogListResultRequest.of(request.sql(), failed.isEmpty(),
                    failed.map(ExecuteResponse::getMessage).orElse(null), responses, SqlOperationLogSourceEnum.AI_TOOL.name()));
            if (failed.isPresent()) {
                throw new AgentDatabaseException("SQL_ERROR", "sql", failed.get().getMessage(),
                        next("db_search_tables", scopeArguments(profile)));
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
            return DbAgentDatabaseResponse.success(scope(profile), new QueryData(columns, rows, "database-text",
                    response.getExecutionMetrics() == null ? null : response.getExecutionMetrics().getTotalDurationMs(), cellWarnings),
                    pagination, Boolean.TRUE.equals(pagination.hasMore()) ? next("db_query", args) : null,
                    cellWarnings.isEmpty() ? List.of() : List.of("Some cells are incomplete; see data.cellWarnings (zero-based row and column)."));
        });
    }

    private List<Source> matchingSources(String search) {
        String needle = search.toLowerCase(Locale.ROOT);
        var matches = new ArrayList<Source>();
        var query = new DbDataSourcePageQueryRequest();
        query.setPageSize(200);
        // Storage providers do not consistently filter aliases. Apply V2 search before V2 pagination.
        for (int page = 1; ; page++) {
            query.setPageNo(page);
            var response = Objects.requireNonNull(storage.listDataSources(query), "Datasource lookup returned no response");
            response.getData().stream().filter(item -> item.getAlias() != null && item.getAlias().toLowerCase(Locale.ROOT).contains(needle))
                    .map(AgentDatabaseServiceImpl::source).forEach(matches::add);
            if (response.getData().isEmpty() || Boolean.FALSE.equals(response.getHasNextPage())
                    || response.getHasNextPage() == null && (response.getTotal() != null
                        ? (long) page * query.getPageSize() >= response.getTotal() : response.getData().size() < query.getPageSize())) break;
        }
        return matches;
    }

    private static Source source(WorkspaceDataSource item) {
        return new Source(String.valueOf(item.getId()), item.getAlias(), item.getType(), item.getEnvType());
    }

    private <T> DbAgentDatabaseResponse<T> scoped(DbAgentDatabaseRequest.Scope request, boolean requireScope,
            Function<ConnectionProfile, DbAgentDatabaseResponse<T>> action) {
        required(request.dataSourceId(), "dataSourceId", next("db_search_datasources", Map.of()));
        long id;
        try { id = Long.parseLong(request.dataSourceId()); }
        catch (NumberFormatException error) { throw invalid("dataSourceId", "Copy the datasource id string from db_search_datasources.", next("db_search_datasources", Map.of())); }
        if (id <= 0) throw invalid("dataSourceId", "Datasource id must be a positive integer string.", next("db_search_datasources", Map.of()));
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
                    throw invalid("schema", "Choose an exact schema name from db_search_schemas.", next("db_search_schemas", args));
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
            throw invalid("database", "Choose an exact database name from db_search_databases.",
                    next("db_search_databases", Map.of("dataSourceId", String.valueOf(profile.getDataSourceId()))));
        }
    }

    private DbAgentDatabaseResponse<Names> names(ConnectionProfile profile, List<Name> items, int page, int size, String tool, Map<String, Object> args) {
        int start = Math.min((page - 1) * size, items.size()), end = Math.min(start + size, items.size());
        var pagination = pageInfo(page, size, end - start, (long) items.size(), end < items.size());
        var nextArgs = new LinkedHashMap<>(args); nextArgs.put("page", page + 1); nextArgs.put("pageSize", size);
        return DbAgentDatabaseResponse.success(scope(profile), new Names(items.subList(start, end), connections.supportDatabase(), connections.supportSchema()),
                pagination, end < items.size() ? next(tool, nextArgs) : null, List.of());
    }

    private static String metadataSchema(String schema, String pattern) {
        if (schema != null && pattern != null) throw invalid("schemaPattern", "Use an exact schema or schemaPattern, not both.", null);
        return schema != null ? AgentMetadataPattern.literal(schema) : AgentMetadataPattern.validate(pattern, "schemaPattern");
    }
    private static Scope metadataScope(ConnectionProfile profile, String schema) {
        return new Scope(String.valueOf(profile.getDataSourceId()), profile.getDbType(), profile.getDatabaseName(), schema);
    }
    private static Map<String, Object> metadataArguments(String id, String database, String schema, String schemaPattern) {
        var args = new LinkedHashMap<String, Object>(); args.put("dataSourceId", id);
        put(args, "database", database); put(args, "schema", schema); put(args, "schemaPattern", schemaPattern);
        return args;
    }
    private static void put(Map<String, Object> args, String key, String value) { if (value != null) args.put(key, value); }
    private static <T> DbAgentDatabaseResponse<List<T>> metadataPage(Scope scope, List<T> items, Integer requestedPage,
            Integer requestedSize, String tool, Map<String, Object> args) {
        int page = page(requestedPage), size = size(requestedSize);
        int start = Math.min((page - 1) * size, items.size()), end = Math.min(start + size, items.size());
        args.put("page", page + 1); args.put("pageSize", size);
        return DbAgentDatabaseResponse.success(scope, items.subList(start, end), pageInfo(page, size, end - start, (long) items.size(), end < items.size()),
                end < items.size() ? next(tool, args) : null, List.of());
    }

    private static boolean isQuery(String type) {
        return type != null && (type.equals("SELECT") || type.startsWith("SHOW_") || type.equals("DESCRIBE") || type.equals("DESCRIBE_FULL"));
    }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static void required(String value, String field, AgentToolNextAction next) {
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
    private static AgentToolNextAction next(String tool, Map<String, Object> arguments) { return new AgentToolNextAction(tool, arguments); }
    private static AgentDatabaseException invalid(String field, String message, AgentToolNextAction next) { return new AgentDatabaseException("INVALID_ARGUMENT", field, message, next); }
}

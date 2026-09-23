package ai.chat2db.community.domain.core.impl.agent;

import ai.chat2db.community.domain.api.constant.agent.AgentDatabaseConstant;
import ai.chat2db.community.domain.api.model.metadata.*;
import ai.chat2db.community.domain.api.model.metadata.extension.MetadataAccessContext;
import ai.chat2db.community.domain.api.model.response.agent.DbAgentDatabaseResponse.ObjectSummary;
import ai.chat2db.community.domain.api.service.agent.AgentMetadataService;
import ai.chat2db.community.domain.core.impl.db.extension.MetadataAccessPolicyManager;
import ai.chat2db.community.tools.exception.agent.AgentDatabaseException;
import ai.chat2db.community.tools.util.AgentTrace;
import ai.chat2db.spi.IDbMetaData;
import ai.chat2db.spi.DefaultMetaService;
import ai.chat2db.spi.model.datasource.ConnectInfo;
import ai.chat2db.spi.model.request.*;
import ai.chat2db.spi.sql.Chat2DBContext;
import ai.chat2db.spi.util.ResultSetUtils;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import java.sql.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** Raw V2 metadata is cached separately; current authorization is applied after every cache lookup. */
@Service
public class AgentMetadataServiceImpl implements AgentMetadataService {
    private static final String[] TABLE_TYPES = {"TABLE", "BASE TABLE", "VIEW", "SYSTEM TABLE", "PARTITIONED TABLE", "MATERIALIZED VIEW"};
    private final MetadataAccessPolicyManager policies;
    private final Supplier<Connection> connection;
    private final Supplier<ConnectInfo> context;
    private final Supplier<IDbMetaData> dialect;
    private final Cache<Key, List<Database>> databaseCache = cache();
    private final Cache<Key, List<Schema>> schemaCache = cache();
    private final Cache<Key, List<Table>> tableCache = cache();
    private final Cache<Key, Description> descriptionCache = cache();
    private final Cache<Key, ObjectSearchResult> objectCache = cache();

    @Autowired
    public AgentMetadataServiceImpl(MetadataAccessPolicyManager policies) {
        this(policies, Chat2DBContext::getConnection, Chat2DBContext::getConnectInfo, Chat2DBContext::getDbMetaData);
    }

    AgentMetadataServiceImpl(MetadataAccessPolicyManager policies, Supplier<Connection> connection,
            Supplier<ConnectInfo> context, Supplier<IDbMetaData> dialect) {
        this.policies = policies; this.connection = connection; this.context = context; this.dialect = dialect;
    }

    @Override
    public List<Database> databases(String databasePattern, boolean refresh) {
        List<Database> raw = cached(databaseCache, key("databases", databasePattern, null, null, null), refresh,
                () -> dialect.get().databases(connection.get()).stream().filter(item -> AgentMetadataPattern.matches(item.getName(), databasePattern)).toList());
        return policies.filter(raw, item -> resource(item.getName(), null, null, null));
    }

    @Override
    public List<Schema> schemas(String database, String schemaPattern, boolean refresh) {
        List<Schema> raw = cached(schemaCache, key("schemas", database, schemaPattern, null, null), refresh, () -> {
            DatabaseMetaData metadata = connection.get().getMetaData();
            try (ResultSet rows = database == null && schemaPattern == null ? metadata.getSchemas()
                    : metadata.getSchemas(database, pattern(metadata, schemaPattern))) {
                List<Schema> schemas = ResultSetUtils.toObjectList(rows, Schema.class);
                schemas.forEach(item -> {
                    if (item.getDatabaseName() == null) item.setDatabaseName(database);
                    item.setSystem(dialect.get().getSystemSchemas().contains(item.getName()));
                });
                return schemas.stream().filter(item -> AgentMetadataPattern.matches(item.getName(), schemaPattern)).toList();
            }
        });
        return policies.filter(raw, item -> resource(item.getDatabaseName(), item.getName(), null, null));
    }

    @Override
    public List<Table> tables(String database, String schemaPattern, String tablePattern, boolean refresh) {
        List<Table> raw = cached(tableCache, key("tables", database, schemaPattern, tablePattern, null), refresh,
                () -> readTables(database, schemaPattern, tablePattern));
        return policies.filter(raw, item -> resource(item.getDatabaseName(), item.getSchemaName(), item.getName(), null));
    }

    @Override
    public ObjectSearchResult objects(String database, String schemaPattern, String objectPattern, List<String> types,
                                      boolean supportsSchemas, boolean refresh) {
        String kinds = String.join(",", new TreeSet<>(types));
        List<ObjectSummary> items = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        List<String> relations = types.stream().filter(type -> type.equals("TABLE") || type.equals("VIEW")).toList();
        if (!relations.isEmpty()) {
            addSearch(items, warnings, search("relations:" + kinds, database, schemaPattern, objectPattern, supportsSchemas,
                    refresh, () -> readRelations(database, schemaPattern, objectPattern, relations, supportsSchemas)));
        }
        if (types.contains("FUNCTION") || types.contains("PROCEDURE")) {
            addSearch(items, warnings, search("routines:" + kinds, database, schemaPattern, objectPattern, supportsSchemas,
                    refresh, () -> readRoutines(database, schemaPattern, objectPattern, types, supportsSchemas)));
        }
        if (types.contains("TRIGGER")) {
            try {
                List<String> scopes = supportsSchemas ? schemas(database, schemaPattern, refresh).stream()
                        .filter(schema -> database == null || Objects.equals(database, schema.getDatabaseName()))
                        .map(Schema::getName).filter(Objects::nonNull).distinct().toList() : Collections.singletonList(null);
                for (String schema : scopes) {
                    if (!policies.isAllowed(resource(database, schema, null, null))) continue;
                    String exactPattern = schema == null ? null : AgentMetadataPattern.literal(schema);
                    addSearch(items, warnings, search("triggers:" + kinds, database, exactPattern, objectPattern,
                            supportsSchemas, refresh,
                            () -> readTriggers(database, schema, objectPattern, supportsSchemas)));
                }
            } catch (RuntimeException error) { // impl-contract: best-effort - other object kinds remain usable.
                warnings.add("TRIGGER lookup unavailable: " + error.getMessage());
            }
        }
        List<ObjectSummary> visible = policies.filter(items, item -> resource(item.database(), item.schema(),
                item.type().equals("TABLE") || item.type().equals("VIEW") ? item.name() : null, null));
        return new ObjectSearchResult(visible, warnings.stream().distinct().toList());
    }

    private ObjectSearchResult search(String kind, String database, String schemaPattern, String objectPattern,
                                      boolean supportsSchemas, boolean refresh, Loader<ObjectSearchResult> loader) {
        return cached(objectCache, key("objects:" + kind + ":" + supportsSchemas, database, schemaPattern, objectPattern, null),
                refresh, () -> {
                    try { return loader.load(); }
                    catch (SQLException | UnsupportedOperationException error) { // impl-contract: best-effort - advertise incomplete metadata, never cache a failed lookup.
                        return new ObjectSearchResult(List.of(), List.of(kind.split(":")[0].toUpperCase(Locale.ROOT)
                                + " lookup unavailable: " + error.getMessage()));
                    }
                });
    }

    private static void addSearch(List<ObjectSummary> items, List<String> warnings, ObjectSearchResult result) {
        items.addAll(result.items()); warnings.addAll(result.warnings());
    }

    private ObjectSearchResult readRelations(String database, String schemaPattern, String objectPattern,
                                            List<String> types, boolean supportsSchemas) throws SQLException {
        DatabaseMetaData metadata = connection.get().getMetaData();
        String[] nativeTypes = Arrays.stream(TABLE_TYPES).filter(type -> types.contains(relationType(type))).toArray(String[]::new);
        List<ObjectSummary> items = new ArrayList<>(); List<String> warnings = new ArrayList<>();
        try (ResultSet rows = metadata.getTables(database, pattern(metadata, schemaPattern), pattern(metadata, objectPattern), nativeTypes)) {
            while (rows.next()) {
                String type = relationType(rows.getString("TABLE_TYPE"));
                if (type == null) { warnings.add("Some relation types returned by the driver are unsupported and were omitted."); continue; }
                if (!types.contains(type)) continue;
                ObjectSummary item = scoped(rows.getString("TABLE_NAME"), type, rows.getString("REMARKS"),
                        rows.getString("TABLE_CAT"), rows.getString("TABLE_SCHEM"), database, schemaPattern,
                        objectPattern, supportsSchemas, exactJdbcSchema(metadata, schemaPattern), warnings);
                if (item != null) items.add(item);
            }
        }
        return new ObjectSearchResult(List.copyOf(items), List.copyOf(warnings));
    }

    private ObjectSearchResult readRoutines(String database, String schemaPattern, String objectPattern,
                                           List<String> types, boolean supportsSchemas) {
        List<String> warnings = new ArrayList<>(); List<Routine> functions = List.of();
        try { functions = readRoutineRows(database, schemaPattern, objectPattern, "FUNCTION", supportsSchemas, warnings); }
        catch (SQLException | UnsupportedOperationException error) { // impl-contract: best-effort - procedure lookup may still be available.
            warnings.add("FUNCTION lookup unavailable: " + error.getMessage());
        }
        List<ObjectSummary> items = new ArrayList<>();
        if (types.contains("FUNCTION")) functions.forEach(item -> items.add(item.object()));
        if (types.contains("PROCEDURE")) {
            try {
                List<Routine> procedures = readRoutineRows(database, schemaPattern, objectPattern, "PROCEDURE", supportsSchemas, warnings);
                for (Routine procedure : procedures) {
                    if (procedure.procedureConfirmed()) { items.add(procedure.object()); continue; }
                    List<Routine> sameName = functions.stream().filter(function -> sameObject(function.object(), procedure.object())).toList();
                    if (sameName.stream().anyMatch(function -> function.specificName() != null
                            && function.specificName().equals(procedure.specificName()))) continue;
                    if (!sameName.isEmpty() && (procedure.specificName() == null || sameName.stream().anyMatch(function -> function.specificName() == null))) {
                        warnings.add("Some same-name PROCEDURE candidates were omitted because the driver returned no specific identity to distinguish them from functions.");
                        continue;
                    }
                    items.add(procedure.object());
                }
            } catch (SQLException | UnsupportedOperationException error) { // impl-contract: best-effort - retain any discovered functions.
                warnings.add("PROCEDURE lookup unavailable: " + error.getMessage());
            }
        }
        Map<List<String>, ObjectSummary> unique = new LinkedHashMap<>();
        for (ObjectSummary item : items) {
            if (unique.putIfAbsent(Arrays.asList(item.database(), item.schema(), item.type(), item.name()), item) != null) {
                warnings.add("Overloaded routines are listed once per name and type; db_describe_objects cannot select a specific signature.");
            }
        }
        return new ObjectSearchResult(List.copyOf(unique.values()), List.copyOf(warnings));
    }

    private List<Routine> readRoutineRows(String database, String schemaPattern, String objectPattern, String type,
                                          boolean supportsSchemas, List<String> warnings) throws SQLException {
        DatabaseMetaData metadata = connection.get().getMetaData();
        String schema = pattern(metadata, schemaPattern); String name = pattern(metadata, objectPattern);
        List<Routine> items = new ArrayList<>();
        // Connector/J encodes ROUTINE_TYPE as procedureNoResult/procedureReturnsResult; same-named
        // functions and procedures share SPECIFIC_NAME, so that field cannot classify MySQL routines.
        boolean mysqlProcedures = type.equals("PROCEDURE") && metadata.getDriverName().startsWith("MySQL Connector");
        try (ResultSet rows = type.equals("FUNCTION") ? metadata.getFunctions(database, schema, name)
                : metadata.getProcedures(database, schema, name)) {
            while (rows.next()) {
                if (mysqlProcedures) {
                    int routineType = rows.getInt("PROCEDURE_TYPE");
                    if (routineType == DatabaseMetaData.procedureReturnsResult) continue;
                    if (routineType != DatabaseMetaData.procedureNoResult) {
                        warnings.add("PROCEDURE candidates with unknown MySQL routine type were omitted.");
                        continue;
                    }
                }
                ObjectSummary item = scoped(rows.getString(type + "_NAME"), type, rows.getString("REMARKS"),
                        rows.getString(type + "_CAT"), rows.getString(type + "_SCHEM"), database, schemaPattern,
                        objectPattern, supportsSchemas, exactJdbcSchema(metadata, schemaPattern), warnings);
                if (item != null) items.add(new Routine(item, rows.getString("SPECIFIC_NAME"), mysqlProcedures));
            }
        }
        return items;
    }

    private ObjectSearchResult readTriggers(String database, String schema, String objectPattern, boolean supportsSchemas) {
        IDbMetaData provider = dialect.get();
        try {
            if (provider.getClass().getMethod("triggers", Connection.class, String.class, String.class)
                    .getDeclaringClass().equals(DefaultMetaService.class)) {
                throw new UnsupportedOperationException("This database driver does not implement trigger metadata.");
            }
        } catch (NoSuchMethodException error) {
            throw new IllegalStateException("Trigger metadata provider is invalid", error);
        }
        List<ObjectSummary> items = new ArrayList<>(); List<String> warnings = new ArrayList<>();
        for (Trigger trigger : provider.triggers(connection.get(), database, schema)) {
            ObjectSummary item = scoped(trigger.getTriggerName(), "TRIGGER", null, trigger.getDatabaseName(),
                    trigger.getSchemaName(), database, schema == null ? null : AgentMetadataPattern.literal(schema),
                    objectPattern, supportsSchemas, schema, warnings);
            if (item != null) items.add(item);
        }
        return new ObjectSearchResult(List.copyOf(items), List.copyOf(warnings));
    }

    private ObjectSummary scoped(String name, String type, String comment, String foundDatabase, String foundSchema,
                                 String database, String schemaPattern, String objectPattern, boolean supportsSchemas,
                                 String exactSchema, List<String> warnings) {
        if (name == null || !AgentMetadataPattern.matches(name, objectPattern)) return null;
        if (foundDatabase != null && database != null && !database.equals(foundDatabase)) return null;
        if (foundDatabase == null) foundDatabase = database; // JDBC catalog and dialect database are exact filters.
        if (supportsSchemas && foundSchema == null) {
            foundSchema = exactSchema;
            if (foundSchema == null) {
                warnings.add(type + " candidates with unknown schema were omitted; specify an exact schema or verify driver metadata support.");
                return null;
            }
        }
        if (!AgentMetadataPattern.matches(foundSchema, schemaPattern)) return null;
        return new ObjectSummary(name, type, comment, foundDatabase, foundSchema);
    }

    private String exactJdbcSchema(DatabaseMetaData metadata, String schemaPattern) throws SQLException {
        String schema = exactPattern(schemaPattern);
        if (schema != null && (schema.contains("%") || schema.contains("_"))) {
            String escape = metadata.getSearchStringEscape();
            if (escape == null || escape.isEmpty()) return null;
        }
        return schema;
    }

    private static String exactPattern(String pattern) {
        if (pattern == null) return null;
        StringBuilder literal = new StringBuilder();
        for (int i = 0; i < pattern.length(); i++) {
            char value = pattern.charAt(i);
            if (value == '\\') { literal.append(pattern.charAt(++i)); }
            else if (value == '%' || value == '_') return null;
            else literal.append(value);
        }
        return literal.toString();
    }

    private static String relationType(String type) {
        if (type == null) return null;
        return switch (type.toUpperCase(Locale.ROOT)) {
            case "TABLE", "BASE TABLE", "SYSTEM TABLE", "PARTITIONED TABLE" -> "TABLE";
            case "VIEW", "MATERIALIZED VIEW" -> "VIEW";
            default -> null;
        };
    }

    private static boolean sameObject(ObjectSummary left, ObjectSummary right) {
        return left.name().equals(right.name()) && Objects.equals(left.database(), right.database())
                && Objects.equals(left.schema(), right.schema());
    }

    private record Routine(ObjectSummary object, String specificName, boolean procedureConfirmed) { }

    @Override
    public Description describe(String database, String schema, String type, String name, boolean refresh) {
        if (type == null || !AgentDatabaseConstant.OBJECT_TYPES.contains(type)) {
            throw new AgentDatabaseException("INVALID_ARGUMENT", "objects", "Unsupported object type: " + type, null);
        }
        boolean relation = type.equals("TABLE") || type.equals("VIEW");
        // The existing metadata policy addresses tables/columns; other definitions use their database/schema scope.
        if (!policies.isAllowed(resource(database, schema, relation ? name : null, null))) {
            throw new AgentDatabaseException("PERMISSION_DENIED", "objects", "Object metadata is not accessible: " + type + " " + name, null);
        }
        Description raw = cached(descriptionCache, key("description:" + type, database, schema, name, null), refresh,
                () -> readDescription(database, schema, type, name));
        if (raw.table() == null) return raw;
        List<TableColumn> visible = policies.filter(raw.table().getColumnList(), item -> resource(database, schema, name, item.getName()));
        Set<String> names = new HashSet<>(visible.stream().map(TableColumn::getName).toList());
        Table filtered = Table.builder().name(name).databaseName(database).schemaName(schema).comment(raw.table().getComment())
                .type(raw.table().getType()).columnList(visible)
                .indexList(raw.table().getIndexList().stream().filter(index -> index.getColumnList() == null
                        || index.getColumnList().stream().allMatch(column -> names.contains(column.getColumnName()))).toList())
                .foreignKeyList(raw.table().getForeignKeyList().stream().filter(fk -> names.contains(fk.getFkColumnName())
                        && policies.isAllowed(resource(fk.getPkTableCat(), fk.getPkTableSchem(), fk.getPkTableName(), fk.getPkColumnName()))).toList()).build();
        boolean complete = visible.size() == raw.table().getColumnList().size();
        List<String> warnings = new ArrayList<>(raw.warnings());
        if (!complete) warnings.add("Some columns are not accessible; the full object definition is omitted. Verify metadata permissions before querying these columns.");
        return new Description(filtered, complete ? raw.definition() : null, List.copyOf(warnings));
    }

    private Description readDescription(String database, String schema, String type, String name) throws SQLException {
        if (!type.equals("TABLE") && !type.equals("VIEW")) {
            return new Description(null, readDefinition(database, schema, type, name),
                    List.of("Definition is returned as provided by the database: it may be CREATE DDL, a source body or an implementation reference."));
        }
        String schemaPattern = schema == null ? null : AgentMetadataPattern.literal(schema);
        String namePattern = AgentMetadataPattern.literal(name);
        List<Table> matches = readTables(database, schemaPattern, namePattern);
        if (matches.isEmpty()) {
            throw new AgentDatabaseException("OBJECT_NOT_FOUND", "objects", type + " not found: " + name, null);
        }
        Table metadata = matches.get(0);
        boolean view = metadata.getType() != null && metadata.getType().toUpperCase(Locale.ROOT).contains("VIEW");
        if (view != type.equals("VIEW")) {
            throw new AgentDatabaseException("OBJECT_TYPE_MISMATCH", "objects",
                    name + " is a " + (view ? "VIEW" : "TABLE") + "; use that object type.", null);
        }
        metadata.setColumnList(readColumns(database, schemaPattern, namePattern, null));
        metadata.setIndexList(List.of()); metadata.setForeignKeyList(List.of());
        List<String> warnings = new ArrayList<>();
        if (!view) readTableKeys(metadata, database, schema, name, warnings);
        String definition = null;
        try { definition = readDefinition(database, schema, type, name); }
        catch (RuntimeException error) { // impl-contract: fallback - report unavailable definitions without fabricating DDL.
            warnings.add("Definition unavailable for " + type + " " + name + "; verify the object name, metadata permissions and driver support.");
        }
        if (view && definition != null) warnings.add("View definition may be CREATE VIEW DDL or only its query body, as provided by the database.");
        return new Description(metadata, definition, List.copyOf(warnings));
    }

    private String readDefinition(String database, String schema, String type, String name) {
        String definition;
        try {
            definition = switch (type) {
                case "TABLE" -> dialect.get().tableDDL(connection.get(), new TableMetadataRequest(database, schema, name));
                case "VIEW" -> {
                    Table object = dialect.get().view(connection.get(), new ViewMetadataRequest(database, schema, name));
                    yield object == null ? null : object.getDdl();
                }
                case "FUNCTION" -> {
                    var object = dialect.get().function(connection.get(), new FunctionMetadataRequest(database, schema, name));
                    yield object == null ? null : object.getFunctionBody();
                }
                case "PROCEDURE" -> {
                    var object = dialect.get().procedure(connection.get(), new ProcedureMetadataRequest(database, schema, name));
                    yield object == null ? null : object.getProcedureBody();
                }
                case "TRIGGER" -> {
                    var object = dialect.get().trigger(connection.get(), new TriggerMetadataRequest(database, schema, name));
                    yield object == null ? null : object.getTriggerBody();
                }
                default -> throw new AgentDatabaseException("INVALID_ARGUMENT", "objects", "Unsupported object type: " + type, null);
            };
        } catch (UnsupportedOperationException error) {
            throw new AgentDatabaseException("UNSUPPORTED_OBJECT_DEFINITION", "objects",
                    "This database driver does not support reading " + type + " definitions.", null, error);
        }
        if (definition == null || definition.isBlank()) {
            throw new AgentDatabaseException("DEFINITION_UNAVAILABLE", "objects",
                    "No definition was returned for " + type + " " + name + ". Verify the exact name, database/schema, permissions and driver support.", null);
        }
        return definition;
    }

    private void readTableKeys(Table metadata, String database, String schema, String name, List<String> warnings) {
        try (ResultSet keys = connection.get().getMetaData().getPrimaryKeys(database, schema, name)) {
            Set<String> primaryColumns = new HashSet<>();
            while (keys.next()) primaryColumns.add(keys.getString("COLUMN_NAME"));
            metadata.getColumnList().forEach(column -> column.setPrimaryKey(primaryColumns.contains(column.getName())));
        } catch (SQLException error) { // impl-contract: best-effort - primary keys supplement column metadata.
            warnings.add("Primary keys unavailable for " + name);
        }
        TableMetadataRequest request = new TableMetadataRequest(database, schema, name);
        try { metadata.setIndexList(dialect.get().indexes(connection.get(), request)); }
        catch (RuntimeException error) { // impl-contract: best-effort - indexes supplement column metadata.
            warnings.add("Indexes unavailable for " + name);
        }
        try { metadata.setForeignKeyList(dialect.get().getImportedKeys(connection.get(), request)); }
        catch (RuntimeException error) { // impl-contract: best-effort - foreign keys supplement column metadata.
            warnings.add("Foreign keys unavailable for " + name);
        }
    }

    private List<Table> readTables(String database, String schemaPattern, String tablePattern) throws SQLException {
        DatabaseMetaData metadata = connection.get().getMetaData();
        try (ResultSet rows = metadata.getTables(database, pattern(metadata, schemaPattern), pattern(metadata, tablePattern), TABLE_TYPES)) {
            List<Table> tables = ResultSetUtils.toObjectList(rows, Table.class);
            tables.forEach(item -> { if (item.getDatabaseName() == null) item.setDatabaseName(database); });
            return tables.stream().filter(item -> AgentMetadataPattern.matches(item.getName(), tablePattern)
                    && AgentMetadataPattern.matches(item.getSchemaName(), schemaPattern)).toList();
        }
    }
    private List<TableColumn> readColumns(String database, String schemaPattern, String tablePattern, String columnPattern) throws SQLException {
        DatabaseMetaData metadata = connection.get().getMetaData();
        try (ResultSet rows = metadata.getColumns(database, pattern(metadata, schemaPattern), pattern(metadata, tablePattern), pattern(metadata, columnPattern))) {
            List<TableColumn> columns = ResultSetUtils.toObjectList(rows, TableColumn.class);
            columns.forEach(item -> { if (item.getDatabaseName() == null) item.setDatabaseName(database); });
            return columns.stream().filter(item -> AgentMetadataPattern.matches(item.getName(), columnPattern)
                    && AgentMetadataPattern.matches(item.getTableName(), tablePattern)
                    && AgentMetadataPattern.matches(item.getSchemaName(), schemaPattern)).toList();
        }
    }
    private String pattern(DatabaseMetaData metadata, String value) throws SQLException {
        return value == null ? null : AgentMetadataPattern.jdbc(value, metadata.getSearchStringEscape());
    }
    private MetadataAccessContext resource(String database, String schema, String table, String column) {
        ConnectInfo info = context.get();
        return MetadataAccessContext.builder().dataSourceId(info.getDataSourceId()).dbType(info.getDbType())
                .databaseName(database).schemaName(schema).tableName(table).columnName(column).operationType("SELECT").build();
    }
    private Key key(String kind, String database, String schemaPattern, String tablePattern, String columnPattern) {
        ConnectInfo info = context.get();
        return new Key(info.getDataSourceId(), info.getDbType(), info.getUrl(), info.getUser(), kind,
                database, schemaPattern, tablePattern, columnPattern);
    }
    private <T> T cached(Cache<Key, T> cache, Key key, boolean refresh, Loader<T> loader) {
        if (refresh) cache.invalidate(key);
        T result = cache.getIfPresent(key);
        boolean hit = result != null;
        if (!hit) {
            try { result = loader.load(); }
            catch (SQLException error) {
                throw new AgentDatabaseException(error instanceof SQLFeatureNotSupportedException ? "UNSUPPORTED_METADATA_FILTER" : "METADATA_ERROR",
                        null, "JDBC " + key.kind + " lookup failed: " + error.getMessage(), null, error);
            }
            if (!(result instanceof ObjectSearchResult search) || search.warnings().isEmpty()) cache.put(key, result);
        }
        var fields = new LinkedHashMap<String, Object>();
        fields.put("kind", key.kind); fields.put("cacheHit", hit); fields.put("refresh", refresh); fields.put("dataSourceId", key.dataSourceId);
        if (key.database != null) fields.put(key.kind.equals("databases") ? "databasePattern" : "database", key.database);
        if (key.schemaPattern != null) fields.put(key.kind.startsWith("description:") ? "schema" : "schemaPattern", key.schemaPattern);
        if (key.tablePattern != null) fields.put(key.kind.startsWith("description:") ? "objectName" : "tablePattern", key.tablePattern);
        if (key.kind.startsWith("description:")) fields.put("objectType", key.kind.substring("description:".length()));
        if (key.columnPattern != null) fields.put("columnPattern", key.columnPattern);
        if (result instanceof List<?> list) fields.put("matchedRows", list.size());
        AgentTrace.record("database.metadata.v2", null, null, fields);
        return result;
    }
    private static <T> Cache<Key, T> cache() {
        return CacheBuilder.newBuilder().maximumSize(128).expireAfterWrite(60, TimeUnit.SECONDS).build();
    }
    private record Key(Long dataSourceId, String dbType, String url, String user, String kind,
                       String database, String schemaPattern, String tablePattern, String columnPattern) { }
    @FunctionalInterface private interface Loader<T> { T load() throws SQLException; }
}

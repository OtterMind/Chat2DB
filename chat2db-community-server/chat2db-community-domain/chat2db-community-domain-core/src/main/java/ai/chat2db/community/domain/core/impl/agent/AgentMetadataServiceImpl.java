package ai.chat2db.community.domain.core.impl.agent;

import ai.chat2db.community.domain.api.model.agent.database.AgentDatabaseException;
import ai.chat2db.community.domain.api.model.metadata.*;
import ai.chat2db.community.domain.api.model.metadata.extension.MetadataAccessContext;
import ai.chat2db.community.domain.api.service.agent.AgentMetadataService;
import ai.chat2db.community.domain.core.impl.db.extension.MetadataAccessPolicyManager;
import ai.chat2db.community.tools.util.AgentTrace;
import ai.chat2db.spi.IDbMetaData;
import ai.chat2db.spi.model.datasource.ConnectInfo;
import ai.chat2db.spi.model.request.TableMetadataRequest;
import ai.chat2db.spi.sql.Chat2DBContext;
import ai.chat2db.spi.util.ResultSetUtils;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.sql.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

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
    private final Cache<Key, List<TableColumn>> columnCache = cache();
    private final Cache<Key, Description> descriptionCache = cache();

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
    public List<TableColumn> columns(String database, String schemaPattern, String tablePattern, String columnPattern, boolean refresh) {
        List<TableColumn> raw = cached(columnCache, key("columns", database, schemaPattern, tablePattern, columnPattern), refresh,
                () -> readColumns(database, schemaPattern, tablePattern, columnPattern));
        var tableScopes = raw.stream().map(item -> resource(item.getDatabaseName(), item.getSchemaName(), item.getTableName(), null)).distinct().toList();
        var allowedTables = new HashSet<>(policies.filter(tableScopes, item -> item));
        List<TableColumn> tableVisible = raw.stream().filter(item -> allowedTables.contains(
                resource(item.getDatabaseName(), item.getSchemaName(), item.getTableName(), null))).toList();
        return policies.filter(tableVisible, item -> resource(item.getDatabaseName(), item.getSchemaName(), item.getTableName(), item.getName()));
    }

    @Override
    public Description describe(String database, String schema, String table, boolean refresh) {
        String schemaPattern = schema == null ? null : AgentMetadataPattern.literal(schema);
        String tablePattern = AgentMetadataPattern.literal(table);
        if (!policies.isAllowed(resource(database, schema, table, null))) {
            throw new AgentDatabaseException("PERMISSION_DENIED", "tables", "Table metadata is not accessible: " + table, null);
        }
        Description raw = cached(descriptionCache, key("description", database, schema, table, null), refresh, () -> {
            List<Table> matches = readTables(database, schemaPattern, tablePattern).stream().filter(item -> table.equals(item.getName())).toList();
            if (matches.isEmpty()) return new Description(null, null, List.of());
            Table metadata = matches.get(0);
            metadata.setColumnList(readColumns(database, schemaPattern, tablePattern, null));
            List<String> warnings = new ArrayList<>();
            try (ResultSet keys = connection.get().getMetaData().getPrimaryKeys(database, schema, table)) {
                Set<String> primaryColumns = new HashSet<>();
                while (keys.next()) primaryColumns.add(keys.getString("COLUMN_NAME"));
                metadata.getColumnList().forEach(column -> column.setPrimaryKey(primaryColumns.contains(column.getName())));
            } catch (SQLException error) { // impl-contract: best-effort - primary keys supplement column metadata.
                warnings.add("Primary keys unavailable for " + table);
            }
            TableMetadataRequest request = new TableMetadataRequest(database, schema, table);
            try { metadata.setIndexList(dialect.get().indexes(connection.get(), request)); }
            catch (RuntimeException error) { // impl-contract: best-effort - indexes supplement column metadata.
                metadata.setIndexList(List.of()); warnings.add("Indexes unavailable for " + table);
            }
            try { metadata.setForeignKeyList(dialect.get().getImportedKeys(connection.get(), request)); }
            catch (RuntimeException error) { // impl-contract: best-effort - foreign keys supplement column metadata.
                metadata.setForeignKeyList(List.of()); warnings.add("Foreign keys unavailable for " + table);
            }
            String ddl = null;
            try { ddl = dialect.get().tableDDL(connection.get(), request); }
            catch (RuntimeException error) { // impl-contract: fallback - structured metadata remains available without DDL.
                warnings.add("DDL unavailable for " + table + "; use structured columns and indexes.");
            }
            return new Description(metadata, ddl, List.copyOf(warnings));
        });
        if (raw.table() == null) return raw;
        List<TableColumn> visible = policies.filter(raw.table().getColumnList(), item -> resource(database, schema, table, item.getName()));
        Set<String> names = new HashSet<>(visible.stream().map(TableColumn::getName).toList());
        Table filtered = Table.builder().name(table).databaseName(database).schemaName(schema).comment(raw.table().getComment())
                .type(raw.table().getType()).columnList(visible)
                .indexList(raw.table().getIndexList().stream().filter(index -> index.getColumnList() == null
                        || index.getColumnList().stream().allMatch(column -> names.contains(column.getColumnName()))).toList())
                .foreignKeyList(raw.table().getForeignKeyList().stream().filter(fk -> names.contains(fk.getFkColumnName())
                        && policies.isAllowed(resource(fk.getPkTableCat(), fk.getPkTableSchem(), fk.getPkTableName(), fk.getPkColumnName()))).toList()).build();
        boolean complete = visible.size() == raw.table().getColumnList().size();
        List<String> warnings = new ArrayList<>(raw.warnings());
        if (!complete) warnings.add("Some columns are not accessible; full DDL is omitted.");
        return new Description(filtered, complete ? raw.ddl() : null, List.copyOf(warnings));
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
            cache.put(key, result);
        }
        var fields = new LinkedHashMap<String, Object>();
        fields.put("kind", key.kind); fields.put("cacheHit", hit); fields.put("refresh", refresh); fields.put("dataSourceId", key.dataSourceId);
        if (key.database != null) fields.put(key.kind.equals("databases") ? "databasePattern" : "database", key.database);
        if (key.schemaPattern != null) fields.put("schemaPattern", key.schemaPattern);
        if (key.tablePattern != null) fields.put("tablePattern", key.tablePattern);
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

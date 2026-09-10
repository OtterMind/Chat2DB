package ai.chat2db.community.domain.core.impl.agent;

import ai.chat2db.community.domain.api.model.agent.database.AgentDatabaseException;
import ai.chat2db.community.domain.api.model.metadata.Database;
import ai.chat2db.community.domain.core.impl.db.extension.MetadataAccessPolicyManager;
import ai.chat2db.spi.IDbMetaData;
import ai.chat2db.spi.model.datasource.ConnectInfo;
import org.junit.jupiter.api.Test;

import javax.sql.rowset.CachedRowSet;
import javax.sql.rowset.RowSetMetaDataImpl;
import javax.sql.rowset.RowSetProvider;
import java.lang.reflect.Proxy;
import java.sql.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

class AgentMetadataServiceImplTest {
    @Test
    void forwardsPatternsToJdbcAndSeparatesCachesByPatternSourceAndRefresh() {
        Fixture f = new Fixture();
        assertEquals(1, f.service.tables("app", "tenant%", "order%", false).size());
        assertEquals("tenant%", f.lastArgs[1]);
        assertEquals("order%", f.lastArgs[2]);
        assertEquals(1, f.tableCalls);
        f.service.tables("app", "tenant%", "order%", false);
        assertEquals(1, f.tableCalls);
        f.service.tables("app", "tenant%", "customer%", false);
        assertEquals(2, f.tableCalls);
        f.service.tables("app", "tenant%", "order%", true);
        assertEquals(3, f.tableCalls);
        f.info.setDataSourceId(2L);
        f.service.tables("app", "tenant%", "order%", false);
        assertEquals(4, f.tableCalls);
        Fixture anotherInstance = new Fixture();
        anotherInstance.service.tables("app", "tenant%", "order%", false);
        assertEquals(1, anotherInstance.tableCalls);
    }

    @Test
    void neverReadsOrWritesV1MetadataCache() {
        Fixture f = new Fixture();
        String key = ai.chat2db.community.domain.core.cache.CacheKey.getTableKey(1L, "app", "tenant_one");
        ai.chat2db.community.domain.core.cache.MemoryCacheManage.put(key, "v1-cache-sentinel");
        try {
            assertEquals("orders", f.service.tables("app", "tenant\\_one", "order%", false).get(0).getName());
            f.service.tables("app", "tenant\\_one", "order%", true);
            assertEquals(2, f.tableCalls);
            assertEquals("v1-cache-sentinel", ai.chat2db.community.domain.core.cache.MemoryCacheManage.<String>get(key));
        } finally { ai.chat2db.community.domain.core.cache.MemoryCacheManage.remove(key); }
    }

    @Test
    void schemaAndColumnPatternsUseDriverEscapeAndCacheHitsRecheckPermissions() {
        Fixture f = new Fixture();
        f.service.schemas("app", "tenant\\_%", false);
        assertEquals("tenant!_%", f.lastArgs[1]);
        var columns = f.service.columns("app", "tenant%", "order%", "%mail%", false);
        assertEquals(1, columns.size());
        assertEquals(Arrays.asList("app", "tenant%", "order%", "%mail%"), Arrays.asList(f.lastArgs));
        f.allowed.set(false);
        assertTrue(f.service.columns("app", "tenant%", "order%", "%mail%", false).isEmpty());
        assertEquals(1, f.columnCalls);
    }

    @Test
    void databaseFilteringAndDescriptionUseOnlyTheV2Caches() {
        Fixture f = new Fixture();
        assertEquals(List.of("sales_main"), f.service.databases("sales\\_%", false).stream().map(Database::getName).toList());
        f.service.databases("sales\\_%", false);
        assertEquals(1, f.databaseCalls);
        assertEquals(List.of("salesXmain"), f.service.databases("salesX%", false).stream().map(Database::getName).toList());
        assertEquals(2, f.databaseCalls);
        assertEquals(true, f.service.describe("app", "tenant_one", "orders", false).table().getColumnList().get(0).getPrimaryKey());
        assertEquals("tenant!_one", f.lastTableArgs[1]);
        assertEquals("orders", f.lastTableArgs[2]);
        assertEquals(1, f.ddlCalls);
        f.service.describe("app", "tenant_one", "orders", false);
        assertEquals(1, f.ddlCalls);
        f.service.describe("app", "tenant_one", "orders", true);
        assertEquals(2, f.ddlCalls);
    }

    @Test
    void jdbcFailuresAreNotCachedAsEmptyMetadata() {
        Fixture f = new Fixture(); f.fail = true;
        assertThrows(AgentDatabaseException.class, () -> f.service.tables("app", null, "order%", false));
        f.fail = false;
        assertEquals(1, f.service.tables("app", null, "order%", false).size());
        assertEquals(2, f.tableCalls);
    }

    @Test
    void escapesWildcardLiteralsAndRejectsInvalidPatterns() {
        assertEquals("order\\_\\%\\\\", AgentMetadataPattern.literal("order_%\\"));
        assertEquals("order!_!%", AgentMetadataPattern.jdbc("order\\_\\%", "!"));
        assertEquals("order__", AgentMetadataPattern.jdbc("order\\_\\%", ""));
        assertTrue(AgentMetadataPattern.matches("sales_main", "sales\\_%"));
        assertFalse(AgentMetadataPattern.matches("salesXmain", "sales\\_%"));
        assertTrue(AgentMetadataPattern.matches("salesXmain", "sales_main"));
        assertThrows(AgentDatabaseException.class, () -> AgentMetadataPattern.validate("bad\\", "tablePattern"));
        assertThrows(AgentDatabaseException.class, () -> AgentMetadataPattern.validate("bad\\x", "schemaPattern"));
    }

    private static final class Fixture {
        final ConnectInfo info = new ConnectInfo();
        final AtomicBoolean allowed = new AtomicBoolean(true);
        int tableCalls, columnCalls, databaseCalls, ddlCalls;
        boolean fail;
        Object[] lastArgs, lastTableArgs;
        final AgentMetadataServiceImpl service;
        Fixture() {
            info.setDataSourceId(1L); info.setDbType("MYSQL"); info.setUrl("jdbc:test"); info.setUser("test");
            DatabaseMetaData jdbc = proxy(DatabaseMetaData.class, (method, args) -> {
                lastArgs = args;
                return switch (method) {
                    case "getSearchStringEscape" -> "!";
                    case "getTables" -> {
                        tableCalls++; lastTableArgs = args;
                        if (fail) throw new SQLFeatureNotSupportedException("patterns unsupported");
                        yield rows(new String[]{"TABLE_CAT", "TABLE_SCHEM", "TABLE_NAME", "TABLE_TYPE", "REMARKS"},
                                new Object[][]{{"app", "tenant_one", "orders", "TABLE", "order table"}});
                    }
                    case "getSchemas" -> rows(new String[]{"TABLE_CATALOG", "TABLE_SCHEM"}, new Object[][]{{"app", "tenant_one"}});
                    case "getPrimaryKeys" -> rows(new String[]{"COLUMN_NAME"}, new Object[][]{{"email"}});
                    case "getColumns" -> {
                        columnCalls++;
                        yield rows(new String[]{"TABLE_CAT", "TABLE_SCHEM", "TABLE_NAME", "COLUMN_NAME", "TYPE_NAME"},
                                new Object[][]{{"app", "tenant_one", "orders", "email", "VARCHAR"}});
                    }
                    default -> throw new AssertionError(method);
                };
            });
            Connection connection = proxy(Connection.class, (method,args) -> jdbc);
            IDbMetaData dialect = proxy(IDbMetaData.class, (method,args) -> switch (method) {
                case "getSystemSchemas", "indexes", "getImportedKeys" -> List.of();
                case "databases" -> { databaseCalls++; yield List.of(Database.builder().name("sales_main").build(), Database.builder().name("salesXmain").build()); }
                case "tableDDL" -> { ddlCalls++; yield "CREATE TABLE orders (email VARCHAR(255))"; }
                default -> throw new AssertionError(method);
            });
            service = new AgentMetadataServiceImpl(new MetadataAccessPolicyManager(List.of(resources -> resources.stream().map(r -> allowed.get()).toList())),
                    () -> connection, () -> info, () -> dialect);
        }
    }
    private interface Call { Object invoke(String method, Object[] args) throws Exception; }
    private static <T> T proxy(Class<T> type, Call call) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (p,m,a) -> call.invoke(m.getName(),a)));
    }
    private static CachedRowSet rows(String[] columns, Object[][] data) throws SQLException {
        RowSetMetaDataImpl metadata = new RowSetMetaDataImpl(); metadata.setColumnCount(columns.length);
        for (int i = 0; i < columns.length; i++) { metadata.setColumnName(i+1,columns[i]); metadata.setColumnLabel(i+1,columns[i]); metadata.setColumnType(i+1,Types.VARCHAR); }
        CachedRowSet result = RowSetProvider.newFactory().createCachedRowSet(); result.setMetaData(metadata);
        for (Object[] row : data) {
            result.moveToInsertRow(); for(int i=0;i<row.length;i++)result.updateObject(i+1,row[i]); result.insertRow(); result.moveToCurrentRow();
        }
        result.beforeFirst(); return result;
    }
}

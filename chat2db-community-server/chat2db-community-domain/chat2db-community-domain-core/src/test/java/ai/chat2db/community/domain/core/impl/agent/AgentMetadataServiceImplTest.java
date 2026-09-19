package ai.chat2db.community.domain.core.impl.agent;

import ai.chat2db.community.domain.api.model.metadata.*;
import ai.chat2db.community.domain.core.impl.db.extension.MetadataAccessPolicyManager;
import ai.chat2db.community.tools.exception.agent.AgentDatabaseException;
import ai.chat2db.spi.IDbMetaData;
import ai.chat2db.spi.model.datasource.ConnectInfo;
import ai.chat2db.spi.model.request.*;
import java.lang.reflect.Proxy;
import java.sql.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.rowset.CachedRowSet;
import javax.sql.rowset.RowSetMetaDataImpl;
import javax.sql.rowset.RowSetProvider;
import org.junit.jupiter.api.Test;

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
    void schemaPatternsUseDriverEscapeAndDescriptionCacheRechecksColumnPermissions() {
        Fixture f = new Fixture();
        f.service.schemas("app", "tenant\\_%", false);
        assertEquals("tenant!_%", f.lastArgs[1]);
        var description = f.service.describe("app", "tenant_one", "TABLE", "orders", false);
        assertNotNull(description.definition());
        assertEquals(1, description.table().getColumnList().size());
        f.columnsAllowed = false;
        var restricted = f.service.describe("app", "tenant_one", "TABLE", "orders", false);
        assertNull(restricted.definition(), "Column restrictions must still hide the full DDL");
        assertTrue(restricted.table().getColumnList().isEmpty());
        assertTrue(restricted.warnings().stream().anyMatch(warning -> warning.contains("permissions")));
        assertFalse(restricted.warnings().toString().contains("db_search_columns"));
        assertEquals(1, f.columnCalls);
        assertEquals(1, f.ddlCalls);
    }

    @Test
    void databaseFilteringAndDescriptionUseOnlyTheV2Caches() {
        Fixture f = new Fixture();
        assertEquals(List.of("sales_main"), f.service.databases("sales\\_%", false).stream().map(Database::getName).toList());
        f.service.databases("sales\\_%", false);
        assertEquals(1, f.databaseCalls);
        assertEquals(List.of("salesXmain"), f.service.databases("salesX%", false).stream().map(Database::getName).toList());
        assertEquals(2, f.databaseCalls);
        assertEquals(true, f.service.describe("app", "tenant_one", "TABLE", "orders", false).table().getColumnList().get(0).getPrimaryKey());
        assertEquals("tenant!_one", f.lastTableArgs[1]);
        assertEquals("orders", f.lastTableArgs[2]);
        assertEquals(1, f.ddlCalls);
        f.service.describe("app", "tenant_one", "TABLE", "orders", false);
        assertEquals(1, f.ddlCalls);
        f.service.describe("app", "tenant_one", "TABLE", "orders", true);
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

    @Test
    void viewsUseTheirOwnDefinitionAndRejectAnIncorrectObjectType() {
        Fixture f = new Fixture();
        f.service.describe("app", "tenant_one", "TABLE", "orders", false);
        f.tableType = "VIEW";
        var view = f.service.describe("app", "tenant_one", "VIEW", "orders", false);
        assertEquals("SELECT email FROM orders", view.definition());
        assertEquals(1, view.table().getColumnList().size());
        assertEquals(List.of(), view.table().getIndexList());
        assertEquals(1, f.ddlCalls);
        assertEquals(1, f.definitionCalls);
        assertEquals(new ViewMetadataRequest("app", "tenant_one", "orders"), f.definitionRequest);
        assertFalse(view.warnings().isEmpty());
        assertEquals("OBJECT_TYPE_MISMATCH", assertThrows(AgentDatabaseException.class,
                () -> f.service.describe("app", "tenant_one", "TABLE", "orders", true)).code());
        assertEquals("OBJECT_NOT_FOUND", assertThrows(AgentDatabaseException.class,
                () -> f.service.describe("app", "tenant_one", "VIEW", "missing", false)).code());
    }

    @Test
    void definitionCacheSeparatesFullIdentityAndRechecksPermissions() {
        Fixture f = new Fixture();
        var function = f.service.describe("app", "one", "FUNCTION", "shared_name", false);
        assertEquals("function definition", function.definition()); assertNull(function.table());
        assertEquals(new FunctionMetadataRequest("app", "one", "shared_name"), f.definitionRequest);
        f.service.describe("app", "one", "FUNCTION", "shared_name", false);
        assertEquals(1, f.definitionCalls);
        f.service.describe("app", "one", "PROCEDURE", "shared_name", false);
        assertEquals(new ProcedureMetadataRequest("app", "one", "shared_name"), f.definitionRequest);
        f.service.describe("app", "one", "TRIGGER", "shared_name", false);
        assertEquals(new TriggerMetadataRequest("app", "one", "shared_name"), f.definitionRequest);
        f.service.describe("app", "two", "FUNCTION", "shared_name", false);
        f.service.describe("other_db", "one", "FUNCTION", "shared_name", false);
        f.info.setDataSourceId(2L);
        f.service.describe("app", "one", "FUNCTION", "shared_name", false);
        assertEquals(6, f.definitionCalls);
        f.service.describe("app", "one", "FUNCTION", "shared_name", true);
        assertEquals(7, f.definitionCalls);
        f.allowed.set(false);
        assertEquals("PERMISSION_DENIED", assertThrows(AgentDatabaseException.class,
                () -> f.service.describe("app", "one", "FUNCTION", "shared_name", false)).code());
        assertEquals(7, f.definitionCalls);
    }

    @Test
    void unavailableAndUnsupportedDefinitionsAreErrorsAndAreNotCached() {
        Fixture f = new Fixture(); f.emptyDefinition = true;
        assertEquals("DEFINITION_UNAVAILABLE", assertThrows(AgentDatabaseException.class,
                () -> f.service.describe("app", "one", "FUNCTION", "missing", false)).code());
        f.emptyDefinition = false;
        assertEquals("function definition", f.service.describe("app", "one", "FUNCTION", "missing", false).definition());
        assertEquals(2, f.definitionCalls);
        f.unsupportedDefinition = true;
        assertEquals("UNSUPPORTED_OBJECT_DEFINITION", assertThrows(AgentDatabaseException.class,
                () -> f.service.describe("app", "one", "TRIGGER", "missing", false)).code());
    }

    @Test
    void objectSearchPreservesTypedIdentityAndForwardsNativeTypeFilters() {
        Fixture f = new Fixture();
        f.tableRows = new Object[][]{{"app", "tenant_one", "shared", "BASE TABLE", "table"},
                {"app", "tenant_one", "shared", "MATERIALIZED VIEW", "view"},
                {"app", "tenant_one", "ignored", "INDEX", null}};
        var tables = f.service.objects("app", "tenant\\_one", "shared", List.of("TABLE"), true, false);
        assertEquals(List.of("TABLE"), tables.items().stream().map(item -> item.type()).toList());
        assertArrayEquals(new String[]{"TABLE", "BASE TABLE", "SYSTEM TABLE", "PARTITIONED TABLE"}, (String[]) f.lastTableArgs[3]);
        assertEquals("tenant!_one", f.lastTableArgs[1]);
        assertEquals("shared", f.lastTableArgs[2]);
        var both = f.service.objects("app", "tenant\\_one", "shared", List.of("TABLE", "VIEW"), true, false);
        assertEquals(Set.of("TABLE", "VIEW"), new HashSet<>(both.items().stream().map(item -> item.type()).toList()));
        assertEquals(2, f.tableCalls);
    }

    @Test
    void objectCacheUsesTypesAndRechecksPermissionsAndSource() {
        Fixture f = new Fixture();
        var types = List.of("TABLE", "VIEW");
        assertEquals(1, f.service.objects("app", "tenant%", "order%", types, true, false).items().size());
        f.service.objects("app", "tenant%", "order%", List.of("VIEW", "TABLE"), true, false);
        assertEquals(1, f.tableCalls);
        f.tablesAllowed = false;
        assertTrue(f.service.objects("app", "tenant%", "order%", types, true, false).items().isEmpty());
        assertEquals(1, f.tableCalls);
        f.tablesAllowed = true;
        f.service.objects("app", "tenant%", "order%", List.of("TABLE"), true, false);
        assertEquals(2, f.tableCalls);
        f.service.objects("app", "tenant%", "order%", types, true, true);
        assertEquals(3, f.tableCalls);
        f.info.setDataSourceId(2L);
        f.service.objects("app", "tenant%", "order%", types, true, false);
        assertEquals(4, f.tableCalls);
    }

    @Test
    void objectSearchRejectsWrongScopeAndOnlyFillsMissingExactSchema() {
        Fixture f = new Fixture();
        f.tableRows = new Object[][]{{"other", "tenant_one", "wrong_db", "TABLE", null},
                {"app", "elsewhere", "wrong_schema", "TABLE", null},
                {null, "tenant_one", "right", "TABLE", null},
                {"app", null, "unknown_schema", "TABLE", null}};
        var broad = f.service.objects("app", "tenant%", null, List.of("TABLE"), true, false);
        assertEquals(List.of("right"), broad.items().stream().map(item -> item.name()).toList());
        assertEquals("app", broad.items().get(0).database());
        assertFalse(broad.warnings().isEmpty());
        var exact = f.service.objects("app", "tenant\\_one", null, List.of("TABLE"), true, false);
        assertEquals(Set.of("right", "unknown_schema"), new HashSet<>(exact.items().stream().map(item -> item.name()).toList()));
        assertTrue(exact.items().stream().allMatch(item -> item.schema().equals("tenant_one")));
        f.escape = "";
        var unsupportedEscape = f.service.objects("app", "tenant\\_one", null, List.of("TABLE"), true, true);
        assertEquals(List.of("right"), unsupportedEscape.items().stream().map(item -> item.name()).toList());
        assertFalse(unsupportedEscape.warnings().isEmpty());
    }

    @Test
    void objectSearchAcceptsCataloglessDatabases() {
        Fixture f = new Fixture();
        f.tableRows = new Object[][]{{null, null, "orders", "TABLE", null}};
        var result = f.service.objects(null, null, null, List.of("TABLE"), false, false);
        assertEquals(1, result.items().size());
        assertNull(result.items().get(0).database());
        assertNull(result.items().get(0).schema());
        assertTrue(result.warnings().isEmpty());
    }

    @Test
    void functionsAreNotMisclassifiedAsProceduresButSameNamedProceduresSurvive() {
        Fixture f = new Fixture();
        f.functionRows = new Object[][]{{"app", "tenant_one", "shared", "function", "shared_f"},
                {"other", "tenant_one", "elsewhere", "function", "elsewhere"}};
        f.procedureRows = new Object[][]{{"app", "tenant_one", "shared", "function copy", "shared_f"},
                {"app", "tenant_one", "shared", "procedure", "shared_p"},
                {"other", "tenant_one", "wrong_database", null, "x"},
                {"app", "other_schema", "wrong_schema", null, "x"}};
        var result = f.service.objects("app", "tenant\\_one", "shared", List.of("FUNCTION", "PROCEDURE"), true, false);
        assertEquals(Set.of("FUNCTION", "PROCEDURE"), new HashSet<>(result.items().stream().map(item -> item.type()).toList()));
        assertTrue(result.items().stream().allMatch(item -> item.name().equals("shared")));
        assertTrue(result.warnings().isEmpty());
        assertArrayEquals(new Object[]{"app", "tenant!_one", "shared"}, f.lastFunctionArgs);
        assertArrayEquals(new Object[]{"app", "tenant!_one", "shared"}, f.lastProcedureArgs);
        var procedures = f.service.objects("app", "tenant\\_one", "shared", List.of("PROCEDURE"), true, false);
        assertEquals(List.of("PROCEDURE"), procedures.items().stream().map(item -> item.type()).toList());
    }

    @Test
    void mysqlRoutineTypeKeepsSameNamedProcedureWhenSpecificNamesAreAlsoIdentical() {
        Fixture f = new Fixture(); f.driverName = "MySQL Connector/J";
        f.tableRows = new Object[][]{{"app", null, "shared", "TABLE", null}};
        f.functionRows = new Object[][]{{"app", null, "shared", "function", "shared"}};
        f.procedureRows = new Object[][]{{"app", null, "shared", "function copy", "shared", 2},
                {"app", null, "shared", "procedure", "shared", 1}};
        var result = f.service.objects("app", null, "shared", List.of("TABLE", "FUNCTION", "PROCEDURE"), false, false);
        assertEquals(Set.of("TABLE", "FUNCTION", "PROCEDURE"), new HashSet<>(result.items().stream().map(item -> item.type()).toList()));
        assertEquals(3, result.items().size());
        assertTrue(result.warnings().isEmpty());
        assertEquals(List.of("PROCEDURE"), f.service.objects("app", null, "shared", List.of("PROCEDURE"), false, false)
                .items().stream().map(item -> item.type()).toList());
    }

    @Test
    void ambiguousProcedureIdentityAndRoutineOverloadsAreExplicitWarnings() {
        Fixture f = new Fixture();
        f.functionRows = new Object[][]{{"app", "tenant_one", "shared", "function", "shared_f"},
                {"app", "tenant_one", "shared", "overload", "shared_f2"}};
        f.procedureRows = new Object[][]{{"app", "tenant_one", "shared", "ambiguous", null}};
        var result = f.service.objects("app", "tenant%", null, List.of("FUNCTION", "PROCEDURE"), true, false);
        assertEquals(1, result.items().size());
        assertEquals("FUNCTION", result.items().get(0).type());
        assertTrue(result.warnings().stream().anyMatch(warning -> warning.contains("specific identity")));
        assertTrue(result.warnings().stream().anyMatch(warning -> warning.contains("Overloaded")));
        f.procedureRows = new Object[][]{{"app", "tenant_one", "shared", "procedure", "shared_p"}};
        assertEquals(2, f.service.objects("app", "tenant%", null, List.of("FUNCTION", "PROCEDURE"), true, false).items().size());
        assertEquals(2, f.procedureCalls, "Incomplete results must not be cached");
    }

    @Test
    void unsupportedKindsDoNotHideSupportedResultsOrCacheFailure() {
        Fixture f = new Fixture(); f.failFunctions = true; f.failTriggers = true;
        f.procedureRows = new Object[][]{{"app", "tenant_one", "shared", null, "shared"}};
        var types = List.of("TABLE", "FUNCTION", "PROCEDURE", "TRIGGER");
        var result = f.service.objects("app", null, null, types, false, false);
        assertEquals(Set.of("TABLE", "PROCEDURE"), new HashSet<>(result.items().stream().map(item -> item.type()).toList()));
        assertTrue(result.warnings().stream().anyMatch(warning -> warning.contains("FUNCTION")));
        assertTrue(result.warnings().stream().anyMatch(warning -> warning.contains("TRIGGER")));
        f.failFunctions = false; f.failTriggers = false;
        result = f.service.objects("app", null, null, types, false, false);
        assertEquals(Set.of("TABLE", "PROCEDURE"), new HashSet<>(result.items().stream().map(item -> item.type()).toList()));
        assertTrue(result.warnings().isEmpty());
        assertEquals(1, f.tableCalls);
        assertEquals(2, f.functionCalls);
        assertEquals(2, f.triggerCalls);
    }

    @Test
    void triggerSearchUsesOnlyMatchingAuthorizedExactSchemasAndRechecksCachedAccess() {
        Fixture f = new Fixture();
        f.schemaRows = new Object[][]{{"app", "tenant_one"}, {"app", "tenant_two"}, {"other", "tenant_bad"}};
        f.deniedSchema = "tenant_two";
        f.triggerRows = List.of(Trigger.builder().triggerName("audit_insert").build(),
                Trigger.builder().databaseName("wrong").schemaName("tenant_one").triggerName("wrong_database").build());
        var result = f.service.objects("app", "tenant%", "audit%", List.of("TRIGGER"), true, false);
        assertEquals(1, result.items().size());
        assertEquals("tenant_one", result.items().get(0).schema());
        assertEquals(List.of("tenant_one"), f.triggerSchemas);
        f.deniedSchema = null;
        result = f.service.objects("app", "tenant%", "audit%", List.of("TRIGGER"), true, false);
        assertEquals(Set.of("tenant_one", "tenant_two"), new HashSet<>(result.items().stream().map(item -> item.schema()).toList()));
        assertEquals(List.of("tenant_one", "tenant_two"), f.triggerSchemas);
        f.allowed.set(false);
        assertTrue(f.service.objects("app", "tenant%", "audit%", List.of("TRIGGER"), true, false).items().isEmpty());
        assertEquals(2, f.triggerCalls);
    }

    @Test
    void unmatchedTriggerSchemaNeverFallsBackToAllSchemas() {
        Fixture f = new Fixture();
        assertTrue(f.service.objects("app", "missing%", null, List.of("TRIGGER"), true, false).items().isEmpty());
        assertEquals(0, f.triggerCalls);
    }

    @Test
    void unsupportedDefaultTriggerProviderIsNotReportedAsACompleteEmptyList() {
        Fixture f = new Fixture();
        var defaultProvider = new ai.chat2db.spi.DefaultMetaService();
        var service = new AgentMetadataServiceImpl(new MetadataAccessPolicyManager(List.of()), () -> null, () -> f.info, () -> defaultProvider);
        var result = service.objects("app", null, null, List.of("TRIGGER"), false, false);
        assertTrue(result.items().isEmpty());
        assertTrue(result.warnings().stream().anyMatch(warning -> warning.contains("does not implement trigger metadata")));
    }

    private static final class Fixture {
        final ConnectInfo info = new ConnectInfo();
        final AtomicBoolean allowed = new AtomicBoolean(true);
        int tableCalls, columnCalls, databaseCalls, ddlCalls;
        boolean fail, emptyDefinition, unsupportedDefinition;
        boolean columnsAllowed = true, tablesAllowed = true, failFunctions, failTriggers;
        String deniedSchema, escape = "!", driverName = "Test JDBC";
        Object[][] tableRows, schemaRows;
        Object[][] functionRows = {}, procedureRows = {};
        List<Trigger> triggerRows = List.of();
        List<String> triggerSchemas = new ArrayList<>();
        int functionCalls, procedureCalls, triggerCalls;
        Object[] lastFunctionArgs, lastProcedureArgs;
        String tableType = "TABLE";
        int definitionCalls;
        Object definitionRequest;
        Object[] lastArgs, lastTableArgs;
        final AgentMetadataServiceImpl service;
        Fixture() {
            info.setDataSourceId(1L); info.setDbType("MYSQL"); info.setUrl("jdbc:test"); info.setUser("test");
            DatabaseMetaData jdbc = proxy(DatabaseMetaData.class, (method, args) -> {
                lastArgs = args;
                return switch (method) {
                    case "getSearchStringEscape" -> escape;
                    case "getDriverName" -> driverName;
                    case "getTables" -> {
                        tableCalls++; lastTableArgs = args;
                        if (fail) throw new SQLFeatureNotSupportedException("patterns unsupported");
                        yield rows(new String[]{"TABLE_CAT", "TABLE_SCHEM", "TABLE_NAME", "TABLE_TYPE", "REMARKS"},
                                tableRows == null ? new Object[][]{{"app", "tenant_one", "orders", tableType, "order table"}} : tableRows);
                    }
                    case "getSchemas" -> rows(new String[]{"TABLE_CATALOG", "TABLE_SCHEM"}, schemaRows == null ? new Object[][]{{"app", "tenant_one"}} : schemaRows);
                    case "getFunctions" -> {
                        functionCalls++; lastFunctionArgs = args;
                        if (failFunctions) throw new SQLFeatureNotSupportedException("functions unsupported");
                        yield rows(new String[]{"FUNCTION_CAT", "FUNCTION_SCHEM", "FUNCTION_NAME", "REMARKS", "SPECIFIC_NAME"}, functionRows);
                    }
                    case "getProcedures" -> {
                        procedureCalls++; lastProcedureArgs = args;
                        yield rows(new String[]{"PROCEDURE_CAT", "PROCEDURE_SCHEM", "PROCEDURE_NAME", "REMARKS", "SPECIFIC_NAME", "PROCEDURE_TYPE"}, Arrays.stream(procedureRows).map(row -> Arrays.copyOf(row, 6)).toArray(Object[][]::new));
                    }
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
                case "view" -> { definitionCalls++; definitionRequest = args[1]; yield Table.builder().ddl("SELECT email FROM orders").build(); }
                case "triggers" -> {
                    triggerCalls++; triggerSchemas.add((String) args[2]);
                    if (failTriggers) throw new UnsupportedOperationException("triggers unsupported");
                    yield triggerRows;
                }
                case "function", "procedure", "trigger" -> {
                    definitionCalls++; definitionRequest = args[1];
                    if (unsupportedDefinition) throw new UnsupportedOperationException("unsupported");
                    String body = emptyDefinition ? null : method + " definition";
                    yield switch (method) {
                        case "function" -> Function.builder().functionBody(body).build();
                        case "procedure" -> Procedure.builder().procedureBody(body).build();
                        default -> Trigger.builder().triggerBody(body).build();
                    };
                }
                default -> throw new AssertionError(method);
            });
            service = new AgentMetadataServiceImpl(new MetadataAccessPolicyManager(List.of(resources -> resources.stream()
                    .map(r -> allowed.get() && (r.getColumnName() == null || columnsAllowed)
                            && (r.getTableName() == null || tablesAllowed)
                            && (deniedSchema == null || !deniedSchema.equals(r.getSchemaName()))).toList())),
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

package ai.chat2db.community.domain.core.impl.agent;

import ai.chat2db.community.domain.api.model.PageResponse;
import ai.chat2db.community.domain.api.model.agent.database.AgentDatabaseRequest.*;
import ai.chat2db.community.domain.api.model.agent.database.AgentDatabaseResult;
import ai.chat2db.community.domain.api.model.agent.database.AgentDatabaseException;
import ai.chat2db.community.domain.api.model.metadata.*;
import ai.chat2db.community.domain.api.model.request.db.DbDlExecuteRequest;
import ai.chat2db.community.domain.api.model.request.datasource.DbDataSourcePageQueryRequest;
import ai.chat2db.community.domain.api.model.storage.WorkspaceDataSource;
import ai.chat2db.community.domain.api.model.result.*;
import ai.chat2db.community.domain.api.model.runtime.ConnectionProfile;
import ai.chat2db.community.domain.api.model.sql.SimpleSqlStatement;
import ai.chat2db.community.domain.api.service.db.*;
import ai.chat2db.community.domain.api.service.agent.AgentMetadataService;
import ai.chat2db.community.domain.api.service.ops.IOpsSqlOperationLogService;
import ai.chat2db.community.domain.api.service.storage.IWorkspaceStorageFacade;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class AgentDatabaseServiceImplTest {
    @Test
    void datasourceSearchFiltersBeforePaginationEvenWhenStorageIgnoresSearch() {
        Fixture f = new Fixture();
        for (int i = 0; i < 203; i++) {
            var source = new WorkspaceDataSource(); source.setId((long) i + 1);
            source.setAlias(i == 0 ? "SALES_main" : i == 202 ? "sales_archive" : "noise_" + i);
            f.sources.add(source);
        }
        var first = f.service.listSources(new Sources("sales_", 1, 1));
        assertEquals(List.of("SALES_main"), first.data().stream().map(AgentDatabaseResult.Source::name).toList());
        assertEquals(2L, first.page().total());
        assertEquals(Map.of("search", "sales_", "page", 2, "pageSize", 1), first.nextAction().arguments());
        assertEquals(2, f.sourceCalls);
        var second = f.service.listSources(new Sources("sales_", 2, 1));
        assertEquals("sales_archive", second.data().get(0).name());
        assertNull(second.nextAction());
        assertTrue(f.service.listSources(new Sources("missing", 1, 50)).data().isEmpty());
        var unfiltered = f.service.listSources(new Sources(null, 2, 200));
        assertEquals(3, unfiltered.data().size());
        assertEquals(203L, unfiltered.page().total());
    }

    @Test
    void explicitScopeIsRequiredAndThePreviousConnectionIsRestored() {
        Fixture f = new Fixture();
        var missing = failure(() -> f.service.listTables(new Tables(null, null, null, null, null, null, null, null, null)));
        assertNotNull(missing);
        assertEquals("MISSING_DATASOURCE", missing.code());
        assertEquals("db_list_datasources", missing.nextAction().tool());
        assertEquals(0, f.binds);
        var database = failure(() -> f.service.listTables(new Tables("7", null, null, null, null, null, null, null, null)));
        assertEquals("database", database.field());
        assertEquals(Map.of("dataSourceId", "7"), database.nextAction().arguments());
        assertSame(f.previous, f.current);
        f.schemas = true;
        var schema = failure(() -> f.service.query(new Query("7", "app", null, "SELECT 1", null, null)));
        assertEquals("schema", schema.field());
        assertEquals("db_list_schemas", schema.nextAction().tool());
        assertFalse(schema.nextAction().arguments().containsKey("schema"));
    }

    @Test
    void queryPreservesColumnsNullLongCellsAndUsesRequestedPage() {
        Fixture f = new Fixture();
        String longText = "line\nwith\ttab\"" + "x".repeat(300);
        List<List<ResultCell>> rows = new ArrayList<>();
        for (int i = 0; i < 75; i++) rows.add(Arrays.asList(ResultCell.of(String.valueOf(i + 1)), ResultCell.of(String.valueOf(i)),
                ResultCell.builder().value(longText.replace("\n", "\\n").replace("\t", "\\t")).rawValue(longText).build(), null));
        f.response.setHeaderList(List.of(
                Header.builder().name("row number").dataType("CHAT2DB_ROW_NUMBER").build(),
                Header.builder().name("id").columnType("INTEGER").build(),
                Header.builder().name("body").columnType("TEXT").build(),
                Header.builder().name("nullable").columnType("TEXT").build()));
        f.response.setDataList(rows);
        var result = f.service.query(new Query("7", "app", null, "SELECT id, body, nullable FROM samples ORDER BY id", 2, 75));
        assertTrue(result.ok(), String.valueOf(result.error()));
        var data = (AgentDatabaseResult.QueryData) result.data();
        assertEquals(75, data.rows().size());
        assertEquals(3, data.columns().size());
        assertEquals("0", data.rows().get(0).get(0));
        assertEquals(longText, data.rows().get(0).get(1));
        assertNull(data.rows().get(0).get(2));
        assertEquals("INTEGER", data.columns().get(0).type());
        assertEquals(2, f.executed.getPageNo());
        assertEquals(75, f.executed.getPageSize());
        assertEquals(3, result.page().nextPage());
        assertEquals(3, result.nextAction().arguments().get("page"));
        assertEquals("7", result.scope().dataSourceId());
        assertEquals(1, f.audits);
        assertSame(f.previous, f.current);
    }

    @Test
    void sqlFailuresAndWriteStatementsAreNotSuccessfulResults() {
        Fixture f = new Fixture();
        f.queryType = "INSERT";
        var blocked = failure(() -> f.service.query(new Query("7", "app", null, "INSERT INTO samples VALUES (1)", null, null)));
        assertEquals("QUERY_REQUIRED", blocked.code());
        assertNull(f.executed);
        f.queryType = "SELECT";
        f.response.setSuccess(false); f.response.setMessage("no such column: missing");
        var failure = failure(() -> f.service.query(new Query("7", "app", null, "SELECT missing FROM samples", null, null)));
        assertNotNull(failure);
        assertEquals("SQL_ERROR", failure.code());
        assertEquals("sql", failure.field());
        assertEquals("db_list_tables", failure.nextAction().tool());
        assertEquals(1, f.audits);
        var invalidPage = failure(() -> f.service.query(new Query("7", "app", null, "SELECT 1", 0, 500)));
        assertNotNull(invalidPage);
    }

    @Test
    void emptyQueryKeepsColumnsAndLargeCellTruncationIsExplicit() {
        Fixture f = new Fixture(); f.response.setHasNextPage(false);
        var empty = f.service.query(new Query("7", "app", null, "SELECT id FROM samples WHERE 1=0", null, null));
        assertEquals(1, ((AgentDatabaseResult.QueryData) empty.data()).columns().size());
        assertEquals(List.of(), ((AgentDatabaseResult.QueryData) empty.data()).rows());
        assertNull(empty.nextAction());
        f.response.setDataList(List.of(List.of(ResultCell.builder().value("preview").truncated(true).sizeChars(1000L).loadedChars(7L).build())));
        var truncated = f.service.query(new Query("7", "app", null, "SELECT body FROM samples", null, null));
        var data = (AgentDatabaseResult.QueryData) truncated.data();
        assertEquals(1000L, data.cellWarnings().get(0).originalCharacters());
        assertFalse(truncated.warnings().isEmpty());
    }

    @Test
    void schemaKeepsStructuredColumnsWhenDdlIsUnavailable() {
        Fixture f = new Fixture();
        var result = f.service.describeTables(new Describe("7", "app", null, List.of("samples"), null));
        assertTrue(result.ok());
        var detail = (AgentDatabaseResult.TableDetail) ((List<?>) result.data()).get(0);
        assertEquals("id", detail.columns().get(0).name());
        assertEquals(false, detail.columns().get(0).nullable());
        assertEquals(true, detail.columns().get(0).primaryKey());
        assertEquals(1, result.warnings().size());
        assertThrows(AgentDatabaseException.class, () -> f.service.describeTables(new Describe("7", "app", null, List.of("samples", "samples"), null)));
    }

    @Test
    void selectValidationRejectsWritesHiddenInSelectSyntax() {
        assertTrue(AgentSelectQueryPolicy.accepts("SELECT id FROM samples ORDER BY id", "SQLITE"));
        assertTrue(AgentSelectQueryPolicy.accepts("WITH x AS (SELECT 1 AS id) SELECT id FROM x", "POSTGRESQL"));
        assertFalse(AgentSelectQueryPolicy.accepts("SELECT * INTO backup FROM samples", "POSTGRESQL"));
        assertFalse(AgentSelectQueryPolicy.accepts("SELECT * FROM samples FOR UPDATE", "POSTGRESQL"));
        assertFalse(AgentSelectQueryPolicy.accepts("SELECT 1; DELETE FROM samples", "MYSQL"));
        assertFalse(AgentSelectQueryPolicy.accepts("WITH x AS (DELETE FROM samples RETURNING id) SELECT * FROM x", "POSTGRESQL"));
    }

    @Test
    void metadataFiltersAreForwardedAndPreservedAcrossPages() {
        Fixture f = new Fixture();
        f.metadataTables = List.of(Table.builder().name("orders_b").databaseName("app").schemaName("tenant_one").build(),
                Table.builder().name("orders_a").databaseName("app").schemaName("tenant_two").build());
        var result = f.service.listTables(new Tables("7", "app", null, null, "tenant%", "order%", 1, 1, true));
        assertEquals("tenant%", f.metadataArgs[1]);
        assertEquals("order%", f.metadataArgs[2]);
        assertEquals(true, f.metadataArgs[3]);
        assertEquals("order%", result.nextAction().arguments().get("tablePattern"));
        assertEquals("tenant%", result.nextAction().arguments().get("schemaPattern"));
        assertEquals(2, result.nextAction().arguments().get("page"));
        assertEquals("tenant_one", result.data().get(0).schema());
        assertNull(result.scope().schema());
        f.service.listTables(new Tables("7", "app", "tenant_one", "order_", null, null, 1, 50, null));
        assertEquals("tenant\\_one", f.metadataArgs[1]);
        assertEquals("%order\\_%", f.metadataArgs[2]);
        assertThrows(AgentDatabaseException.class, () -> f.service.listTables(new Tables("7", "app", "tenant_one", null, "%", "order%", 1, 50, null)));
    }

    private static AgentDatabaseException failure(java.util.function.Supplier<AgentDatabaseResult<?>> operation) {
        return assertThrows(AgentDatabaseException.class, operation::get);
    }

    private static final class Fixture {
        ConnectionProfile previous = new ConnectionProfile(), current = previous;
        boolean schemas; int binds, audits; String queryType = "SELECT";
        DbDlExecuteRequest executed;
        Object[] metadataArgs;
        List<Table> metadataTables = List.of();
        List<WorkspaceDataSource> sources = new ArrayList<>();
        int sourceCalls;
        ExecuteResponse response = new ExecuteResponse();
        AgentDatabaseServiceImpl service;
        Fixture() {
            response.setSuccess(true); response.setHasNextPage(true); response.setDataList(List.of());
            response.setHeaderList(List.of(Header.builder().name("id").columnType("INTEGER").build()));
            IDbConnectionContextService connection = proxy(IDbConnectionContextService.class, (method, args) -> switch (method) {
                case "currentProfileSnapshot" -> current;
                case "buildProfile" -> { var p = new ConnectionProfile(); p.setDataSourceId(7L); p.setDbType("SQLITE"); p.setDatabaseName("app"); yield p; }
                case "bindProfile" -> { current = (ConnectionProfile) args[0]; binds++; yield null; }
                case "clear" -> { current = null; yield null; }
                case "supportDatabase" -> true;
                case "supportSchema" -> schemas;
                case "getImportedKeys" -> List.of();
                default -> throw new AssertionError(method);
            });
            AgentMetadataService metadata = proxy(AgentMetadataService.class, (method, args) -> switch (method) {
                case "tables" -> { metadataArgs = args; yield metadataTables; }
                case "describe" -> new AgentMetadataService.Description(Table.builder().name("samples")
                        .columnList(List.of(TableColumn.builder().name("id").columnType("INTEGER").nullable(0).primaryKey(true).build())).build(),
                        null, List.of("DDL unsupported"));
                default -> List.of();
            });
            IDbDlTemplateService executor = proxy(IDbDlTemplateService.class, (method, args) -> { executed = (DbDlExecuteRequest) args[0]; return List.of(response); });
            IDbSqlService sql = proxy(IDbSqlService.class, (method, args) -> { var statement = new SimpleSqlStatement(); statement.setSqlType(queryType); return List.of(statement); });
            IOpsSqlOperationLogService audit = proxy(IOpsSqlOperationLogService.class, (method, args) -> { audits++; return null; });
            service = new AgentDatabaseServiceImpl(proxy(IWorkspaceStorageFacade.class, (m,a) -> {
                sourceCalls++;
                var request = (DbDataSourcePageQueryRequest) a[0];
                int start = Math.min((request.getPageNo() - 1) * request.getPageSize(), sources.size());
                return PageResponse.of(sources.subList(start, Math.min(start + request.getPageSize(), sources.size())),
                        (long) sources.size(), request.getPageNo(), request.getPageSize());
            }), connection,
                    metadata, executor, sql, audit);
        }
    }
    private interface Call { Object invoke(String method, Object[] args); }
    private static <T> T proxy(Class<T> type, Call call) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (p,m,a) -> call.invoke(m.getName(), a)));
    }
}

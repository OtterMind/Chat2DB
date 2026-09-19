package ai.chat2db.community.domain.core.impl.agent;

import ai.chat2db.community.domain.api.enums.agent.AgentApprovalDecision;
import ai.chat2db.community.domain.api.model.PageResponse;
import ai.chat2db.community.domain.api.model.agent.tool.AgentToolExecutionContext;
import ai.chat2db.community.domain.api.model.metadata.*;
import ai.chat2db.community.domain.api.model.request.agent.DbAgentDatabaseRequest.*;
import ai.chat2db.community.domain.api.model.request.datasource.DbDataSourcePageQueryRequest;
import ai.chat2db.community.domain.api.model.request.db.DbDlExecuteRequest;
import ai.chat2db.community.domain.api.model.request.runtime.DbConnectionContextRequest;
import ai.chat2db.community.domain.api.model.response.agent.DbAgentDatabaseResponse;
import ai.chat2db.community.domain.api.model.result.*;
import ai.chat2db.community.domain.api.model.runtime.ConnectionProfile;
import ai.chat2db.community.domain.api.model.sql.SimpleSqlStatement;
import ai.chat2db.community.domain.api.model.storage.WorkspaceDataSource;
import ai.chat2db.community.domain.api.service.agent.AgentApprovalService;
import ai.chat2db.community.domain.api.service.agent.AgentMetadataService;
import ai.chat2db.community.domain.api.service.agent.IAiAgentChartService;
import ai.chat2db.community.domain.api.service.db.*;
import ai.chat2db.community.domain.api.service.ops.IOpsSqlOperationLogService;
import ai.chat2db.community.domain.api.service.storage.IWorkspaceStorageFacade;
import ai.chat2db.community.tools.exception.agent.AgentDatabaseException;
import ai.chat2db.community.tools.model.agent.runtime.AgentRuntimeEvent;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

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
        assertEquals(List.of("SALES_main"), first.data().stream().map(DbAgentDatabaseResponse.Source::name).toList());
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
        var missing = failure(() -> f.service.searchObjects(new ObjectSearch(null, null, null, null, null, null, null, null, null, null)));
        assertNotNull(missing);
        assertEquals("MISSING_DATASOURCE", missing.code());
        assertEquals("db_search_datasources", missing.nextAction().tool());
        assertEquals(0, f.binds);
        var database = failure(() -> f.service.searchObjects(new ObjectSearch("7", null, null, null, null, null, null, null, null, null)));
        assertEquals("database", database.field());
        assertEquals(Map.of("dataSourceId", "7"), database.nextAction().arguments());
        assertSame(f.previous, f.current);
        f.schemas = true;
        var schema = failure(() -> f.service.query(new Query("7", "app", null, "SELECT 1", null, null), null));
        assertEquals("schema", schema.field());
        assertEquals("db_search_schemas", schema.nextAction().tool());
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
        var result = f.service.query(new Query("7", "app", null, "SELECT id, body, nullable FROM samples ORDER BY id", 2, 75), null);
        assertTrue(result.ok(), String.valueOf(result.error()));
        var data = result.data().results().get(0).data();
        assertEquals(75, data.rows().size());
        assertEquals(3, data.columns().size());
        assertEquals("0", data.rows().get(0).get(0));
        assertEquals(longText, data.rows().get(0).get(1));
        assertNull(data.rows().get(0).get(2));
        assertEquals("INTEGER", data.columns().get(0).type());
        assertEquals(2, f.executed.getPageNo());
        assertEquals(75, f.executed.getPageSize());
        assertTrue(f.executed.isFullResultValues());
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
        var blocked = failure(() -> f.service.query(new Query("7", "app", null, "INSERT INTO samples VALUES (1)", null, null), null));
        assertEquals("APPROVAL_REQUIRED", blocked.code());
        assertNull(f.executed);
        f.queryType = "SELECT";
        f.response.setSuccess(false); f.response.setMessage("no such column: missing");
        var failedResult = f.service.query(new Query("7", "app", null, "SELECT missing FROM samples", null, null), null);
        assertFalse(failedResult.ok());
        var failure = failedResult.error();
        assertNotNull(failure);
        assertEquals("SQL_ERROR", failure.code());
        assertEquals("sql", failure.field());
        assertNull(failedResult.nextAction());
        assertEquals(1, f.audits);
        var invalidPage = failure(() -> f.service.query(new Query("7", "app", null, "SELECT 1", 0, 500), null));
        assertNotNull(invalidPage);
    }

    @Test
    void emptyQueryKeepsColumnsAndLargeCellTruncationIsExplicit() {
        Fixture f = new Fixture(); f.response.setHasNextPage(false);
        var empty = f.service.query(new Query("7", "app", null, "SELECT id FROM samples WHERE 1=0", null, null), null);
        assertEquals(1, empty.data().results().get(0).data().columns().size());
        assertEquals(List.of(), empty.data().results().get(0).data().rows());
        assertNull(empty.nextAction());
        f.response.setDataList(List.of(List.of(ResultCell.builder().value("preview").truncated(true).sizeChars(1000L).loadedChars(7L).build())));
        var truncated = f.service.query(new Query("7", "app", null, "SELECT body FROM samples", null, null), null);
        var data = truncated.data().results().get(0).data();
        assertEquals(1000L, data.cellWarnings().get(0).originalCharacters());
        assertFalse(truncated.warnings().isEmpty());
    }

    @Test
    void descriptionKeepsWarningsWhenDdlIsUnavailableWithoutDuplicatingMetadata() throws Exception {
        Fixture f = new Fixture();
        var result = f.service.describeObjects(new Describe("7", "app", null, List.of(new ObjectRef("TABLE", "samples")), null));
        assertTrue(result.ok());
        var detail = (DbAgentDatabaseResponse.ObjectDetail) ((List<?>) result.data()).get(0);
        assertEquals("samples", detail.name());
        assertEquals("TABLE", detail.type());
        assertNull(detail.definition());
        assertCompactDescription(detail);
        assertEquals(1, result.warnings().size());
        assertThrows(AgentDatabaseException.class, () -> f.service.describeObjects(new Describe("7", "app", null, List.of(new ObjectRef("TABLE", "samples"), new ObjectRef("TABLE", "samples")), null)));
    }

    @Test
    void descriptionReturnsDatabaseDdlVerbatimAsItsOnlyStructure() throws Exception {
        Fixture f = new Fixture();
        f.definition = "CREATE TABLE samples (id INTEGER PRIMARY KEY, parent_id INTEGER REFERENCES parents(id));";
        var response = f.service.describeObjects(new Describe("7", "app", null,
                List.of(new ObjectRef("TABLE", "samples")), null));
        assertEquals(f.definition, response.data().get(0).definition());
        assertCompactDescription(response.data().get(0));
        assertTrue(response.warnings().isEmpty());
    }

    private void assertCompactDescription(DbAgentDatabaseResponse.ObjectDetail detail) throws Exception {
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        for (String encoded : List.of(json.writeValueAsString(detail), com.alibaba.fastjson2.JSON.toJSONString(detail))) {
            var object = json.readTree(encoded);
            Set<String> fields = new HashSet<>();
            object.fieldNames().forEachRemaining(fields::add);
            assertTrue(Set.of("name", "type", "comment", "definition").containsAll(fields));
            assertFalse(object.has("columns"));
            assertFalse(object.has("indexes"));
            assertFalse(object.has("foreignKeys"));
        }
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
        var result = f.service.searchObjects(new ObjectSearch("7", "app", null, null, "tenant%", "order%", List.of("TABLE", "FUNCTION"), 1, 1, true));
        assertEquals("tenant%", f.metadataArgs[1]);
        assertEquals("order%", f.metadataArgs[2]);
        assertEquals(List.of("TABLE", "FUNCTION"), f.metadataArgs[3]);
        assertEquals(true, f.metadataArgs[5]);
        assertEquals("order%", result.nextAction().arguments().get("objectPattern"));
        assertEquals("tenant%", result.nextAction().arguments().get("schemaPattern"));
        assertEquals(2, result.nextAction().arguments().get("page"));
        assertEquals("tenant_one", result.data().get(0).schema());
        assertEquals("db_search_objects", result.nextAction().tool());
        assertEquals(List.of("TABLE", "FUNCTION"), result.nextAction().arguments().get("types"));
        assertNull(result.scope().schema());
        f.service.searchObjects(new ObjectSearch("7", "app", "tenant_one", "order_", null, null, null, 1, 50, null));
        assertEquals("tenant\\_one", f.metadataArgs[1]);
        assertEquals("%order\\_%", f.metadataArgs[2]);
        assertThrows(AgentDatabaseException.class, () -> f.service.searchObjects(new ObjectSearch("7", "app", "tenant_one", null, "%", "order%", null, 1, 50, null)));
    }

    @Test
    void objectSearchKeepsTypeIdentityWarningsAndStablePagination() {
        Fixture f = new Fixture();
        f.metadataObjects = List.of(
                new DbAgentDatabaseResponse.ObjectSummary("shared", "TABLE", "table", "app", "public"),
                new DbAgentDatabaseResponse.ObjectSummary("shared", "FUNCTION", "function", "app", "public"),
                new DbAgentDatabaseResponse.ObjectSummary("shared", "VIEW", "view", "app", "public"));
        f.metadataWarnings = List.of("TRIGGER lookup is unavailable");
        var types = ai.chat2db.community.domain.api.constant.agent.AgentDatabaseConstant.OBJECT_TYPES;
        var first = f.service.searchObjects(new ObjectSearch("7", "app", "public", null, null,
                "shared", types, 1, 2, null));
        assertEquals(List.of("FUNCTION", "TABLE"), first.data().stream().map(DbAgentDatabaseResponse.ObjectSummary::type).toList());
        assertEquals(3L, first.page().total());
        assertEquals(f.metadataWarnings, first.warnings());
        assertEquals("shared", first.nextAction().arguments().get("objectPattern"));
        assertEquals("public", first.nextAction().arguments().get("schema"));
        assertEquals(types, first.nextAction().arguments().get("types"));
        assertEquals(ai.chat2db.community.domain.api.constant.agent.AgentDatabaseConstant.OBJECT_TYPES, f.metadataArgs[3]);
        var last = f.service.searchObjects(new ObjectSearch("7", "app", "public", null, null,
                "shared", types, 2, 2, null));
        assertEquals(List.of("VIEW"), last.data().stream().map(DbAgentDatabaseResponse.ObjectSummary::type).toList());
        assertFalse(last.page().hasMore());
        assertNull(last.nextAction());
        for (List<String> invalidTypes : List.of(List.<String>of(), List.of("INDEX"), List.of("TABLE", "TABLE"),
                Arrays.asList("TABLE", null))) {
            assertEquals("types", failure(() -> f.service.searchObjects(new ObjectSearch("7", "app", "public",
                    null, null, null, invalidTypes, 1, 50, null))).field());
        }
    }

    @Test
    void objectSearchDefaultsToTablesOnEveryPage() {
        Fixture f = new Fixture();
        f.metadataTables = List.of(Table.builder().name("a").databaseName("app").build(),
                Table.builder().name("b").databaseName("app").build());
        var first = f.service.searchObjects(new ObjectSearch("7", "app", null, null, null,
                null, null, 1, 1, null));
        assertEquals(List.of("TABLE"), f.metadataArgs[3]);
        assertEquals("a", first.data().get(0).name());
        assertFalse(first.nextAction().arguments().containsKey("types"));
        assertEquals(2, first.nextAction().arguments().get("page"));
        var next = new com.fasterxml.jackson.databind.ObjectMapper().convertValue(first.nextAction().arguments(), ObjectSearch.class);
        var second = f.service.searchObjects(next);
        assertEquals(List.of("TABLE"), f.metadataArgs[3]);
        assertEquals("b", second.data().get(0).name());
        assertNull(second.nextAction());
    }

    @Test
    void mismatchedTableDescriptionExplicitlySearchesViewsDespiteTableDefault() {
        Fixture f = new Fixture();
        f.metadataFailure = new AgentDatabaseException("OBJECT_TYPE_MISMATCH", "objects", "sales_% is a VIEW", null);
        var error = failure(() -> f.service.describeObjects(new Describe("7", "app", null,
                List.of(new ObjectRef("TABLE", "sales_%")), null)));
        assertEquals("db_search_objects", error.nextAction().tool());
        assertEquals(List.of("TABLE", "VIEW"), error.nextAction().arguments().get("types"));
        assertEquals("sales\\_\\%", error.nextAction().arguments().get("objectPattern"));
    }

    @Test
    void objectDefinitionsRequireFullScopeAndAllowSameNameWithDifferentTypes() {
        Fixture f = new Fixture(); f.schemas = true;
        var objects = List.of(new ObjectRef("TABLE", "samples"), new ObjectRef("FUNCTION", "samples"));
        assertEquals("dataSourceId", failure(() -> f.service.describeObjects(new Describe(null, "app", "public", objects, null))).field());
        assertEquals("database", failure(() -> f.service.describeObjects(new Describe("7", null, "public", objects, null))).field());
        assertEquals("schema", failure(() -> f.service.describeObjects(new Describe("7", "app", null, objects, null))).field());
        assertNull(f.metadataArgs);
        var result = f.service.describeObjects(new Describe("8", "other_db", "tenant_two", objects, true));
        assertEquals(new DbAgentDatabaseResponse.Scope("8", "SQLITE", "other_db", "tenant_two"), result.scope());
        assertEquals(List.of("TABLE", "FUNCTION"), result.data().stream().map(DbAgentDatabaseResponse.ObjectDetail::type).toList());
        assertEquals("definition of FUNCTION", result.data().get(1).definition());
        assertEquals(List.of("other_db", "tenant_two", "FUNCTION", "samples", true), Arrays.asList(f.metadataArgs));
        assertSame(f.previous, f.current);
        for (var invalid : Arrays.asList(new ObjectRef(null, "x"), new ObjectRef("SEQUENCE", "x"), new ObjectRef("VIEW", " "), null)) {
            assertEquals("objects", failure(() -> f.service.describeObjects(new Describe("7", "app", "public", Collections.singletonList(invalid), null))).field());
        }
    }

    @Test
    void selectBatchExecutesWithoutApprovalAndPreservesEveryResult() {
        Fixture f = new Fixture();
        f.statements = List.of(statement("SELECT 1", "SELECT"), statement("SELECT 2", "SELECT"));
        f.resultBatch = List.of(f.response, f.response);
        var result = f.service.query(new Query("7", "app", null, "SELECT 1; SELECT 2", null, null), f.context());
        assertTrue(result.ok());
        assertTrue(result.data().readOnly());
        assertEquals(2, result.data().statementCount());
        assertEquals(2, result.data().results().size());
        assertEquals(0, f.decisions);
        assertEquals(1, f.executions);
        assertFalse(f.executed.isSingle());
    }

    @Test
    void mixedBatchRequiresOneApprovalBeforeAnyStatementExecutes() {
        Fixture f = new Fixture(); f.approved = true;
        String sql = "SELECT 1; UPDATE samples SET id=2";
        f.statements = List.of(statement("SELECT 1", "SELECT"), statement("UPDATE samples SET id=2", "UPDATE"));
        ExecuteResponse update = new ExecuteResponse(); update.setSuccess(true); update.setUpdateCount(4);
        f.resultBatch = List.of(f.response, update);
        var result = f.service.query(new Query("7", "app", null, sql, null, null), f.context());
        assertTrue(result.ok());
        assertFalse(result.data().readOnly());
        assertEquals(4, result.data().results().get(1).data().affectedRows());
        assertNull(result.nextAction());
        assertEquals(1, f.decisions);
        assertEquals(1, f.executions);
        assertEquals(sql, f.events.get(0).payload().get("command"));
        assertEquals("7", f.events.get(0).payload().get("dataSourceId"));
        assertEquals("app", f.events.get(0).payload().get("database"));
        assertEquals(sql, f.executed.getSql());
    }

    @Test
    void rejectionOrCancellationExecutesNothingIncludingLeadingSelect() {
        for (boolean cancelled : List.of(false, true)) {
            Fixture f = new Fixture(); f.approved = cancelled; f.cancelDuringApproval = cancelled;
            f.statements = List.of(statement("SELECT 1", "SELECT"), statement("DELETE FROM samples", "DELETE"));
            var error = failure(() -> f.service.query(new Query("7", "app", null,
                    "SELECT 1; DELETE FROM samples", null, null), f.context()));
            assertEquals("APPROVAL_DENIED", error.code());
            assertEquals(1, f.decisions);
            assertEquals(0, f.executions);
        }
    }

    @Test
    void batchFailureReturnsEarlierOutcomesWithoutSuggestingReplay() {
        Fixture f = new Fixture(); f.approved = true;
        f.statements = List.of(statement("UPDATE samples SET id=2", "UPDATE"), statement("SELECT missing", "SELECT"), statement("SELECT 3", "SELECT"));
        ExecuteResponse update = new ExecuteResponse(); update.setSuccess(true); update.setUpdateCount(1);
        ExecuteResponse failed = new ExecuteResponse(); failed.setSuccess(false); failed.setMessage("unknown column");
        f.resultBatch = List.of(update, failed);
        var result = f.service.query(new Query("7", "app", null,
                "UPDATE samples SET id=2; SELECT missing; SELECT 3", null, null), f.context());
        assertFalse(result.ok());
        assertEquals(3, result.data().statementCount());
        assertEquals(2, result.data().results().size());
        assertEquals(1, result.data().results().get(0).data().affectedRows());
        assertEquals("SQL_ERROR", result.data().results().get(1).error().code());
        assertNull(result.nextAction());
        assertFalse(f.executed.getErrorContinue());
    }

    private static SimpleSqlStatement statement(String sql, String type) {
        SimpleSqlStatement statement = new SimpleSqlStatement(sql);
        statement.setSqlType(type);
        return statement;
    }

    private static AgentDatabaseException failure(Supplier<DbAgentDatabaseResponse<?>> operation) {
        return assertThrows(AgentDatabaseException.class, operation::get);
    }

    private static final class Fixture {
        ConnectionProfile previous = new ConnectionProfile(), current = previous;
        boolean schemas; int binds, audits; String queryType = "SELECT";
        DbDlExecuteRequest executed;
        int executions, decisions;
        boolean approved;
        boolean cancelDuringApproval;
        final AtomicBoolean active = new AtomicBoolean(true);
        final List<AgentRuntimeEvent> events = new ArrayList<>();
        List<SimpleSqlStatement> statements;
        List<ExecuteResponse> resultBatch;
        Object[] metadataArgs;
        AgentDatabaseException metadataFailure;
        String definition;
        List<Table> metadataTables = List.of();
        List<DbAgentDatabaseResponse.ObjectSummary> metadataObjects;
        List<String> metadataWarnings = List.of();
        List<WorkspaceDataSource> sources = new ArrayList<>();
        int sourceCalls;
        ExecuteResponse response = new ExecuteResponse();
        AgentDatabaseServiceImpl service;
        AgentToolExecutionContext context() {
            return new AgentToolExecutionContext(
                    "session", "run", "call", 1L, events::add, active::get);
        }
        Fixture() {
            response.setSuccess(true); response.setHasNextPage(true); response.setDataList(List.of());
            response.setHeaderList(List.of(Header.builder().name("id").columnType("INTEGER").build()));
            IDbConnectionContextService connection = proxy(IDbConnectionContextService.class, (method, args) -> switch (method) {
                case "currentProfileSnapshot" -> current;
                case "buildProfile" -> {
                    var request = (DbConnectionContextRequest) args[0];
                    var p = new ConnectionProfile(); p.setDataSourceId(request.getDataSourceId()); p.setDbType("SQLITE");
                    p.setDatabaseName(request.getDatabaseName()); p.setSchemaName(request.getSchemaName()); yield p;
                }
                case "bindProfile" -> { current = (ConnectionProfile) args[0]; binds++; yield null; }
                case "clear" -> { current = null; yield null; }
                case "supportDatabase" -> true;
                case "supportSchema" -> schemas;
                case "getImportedKeys" -> List.of();
                default -> throw new AssertionError(method);
            });
            AgentMetadataService metadata = proxy(AgentMetadataService.class, (method, args) -> switch (method) {
                case "objects" -> {
                    metadataArgs = args;
                    yield new AgentMetadataService.ObjectSearchResult(metadataObjects == null ? metadataTables.stream()
                            .map(t -> new DbAgentDatabaseResponse.ObjectSummary(t.getName(), "TABLE", t.getComment(),
                                    t.getDatabaseName(), t.getSchemaName())).toList() : metadataObjects, metadataWarnings);
                }
                case "describe" -> {
                    metadataArgs = args;
                    if (metadataFailure != null) throw metadataFailure;
                    yield args[2].equals("TABLE") || args[2].equals("VIEW")
                            ? new AgentMetadataService.Description(Table.builder().name("samples")
                                .columnList(List.of(TableColumn.builder().name("id").columnType("INTEGER").nullable(0).primaryKey(true).build()))
                                .indexList(List.of()).foreignKeyList(List.of()).build(), definition,
                                definition == null ? List.of("DDL unsupported by the database driver") : List.of())
                            : new AgentMetadataService.Description(null, "definition of " + args[2], List.of());
                }
                default -> List.of();
            });
            IDbDlTemplateService executor = proxy(IDbDlTemplateService.class, (method, args) -> { executed = (DbDlExecuteRequest) args[0]; executions++; return resultBatch == null ? List.of(response) : resultBatch; });
            IDbSqlService sql = proxy(IDbSqlService.class, (method, args) -> { if (statements != null) return statements; var statement = new SimpleSqlStatement((String) args[0]); statement.setSqlType(queryType); return List.of(statement); });
            IOpsSqlOperationLogService audit = proxy(IOpsSqlOperationLogService.class, (method, args) -> { audits++; return null; });
            service = new AgentDatabaseServiceImpl(proxy(IWorkspaceStorageFacade.class, (m,a) -> {
                sourceCalls++;
                var request = (DbDataSourcePageQueryRequest) a[0];
                int start = Math.min((request.getPageNo() - 1) * request.getPageSize(), sources.size());
                return PageResponse.of(sources.subList(start, Math.min(start + request.getPageSize(), sources.size())),
                        (long) sources.size(), request.getPageNo(), request.getPageSize());
            }), connection,
                    metadata, executor, sql, audit, proxy(AgentApprovalService.class, (m,a) -> {
                        decisions++;
                        ((Runnable) a[2]).run();
                        if (cancelDuringApproval) active.set(false);
                        return approved && ((BooleanSupplier) a[3]).getAsBoolean()
                                ? AgentApprovalDecision.ALLOW_ONCE
                                : AgentApprovalDecision.DENY;
                    }), proxy(IAiAgentChartService.class, (method, arguments) -> arguments[0]));
        }
    }
    private interface Call { Object invoke(String method, Object[] args); }
    private static <T> T proxy(Class<T> type, Call call) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (p,m,a) -> call.invoke(m.getName(), a)));
    }
}

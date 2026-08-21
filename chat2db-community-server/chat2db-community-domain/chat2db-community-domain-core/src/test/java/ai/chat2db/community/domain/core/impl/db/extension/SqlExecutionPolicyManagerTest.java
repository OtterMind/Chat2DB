package ai.chat2db.community.domain.core.impl.db.extension;

import ai.chat2db.community.domain.api.enums.plugin.DataTypeEnum;
import ai.chat2db.community.domain.api.model.result.ExecuteResponse;
import ai.chat2db.community.domain.api.model.result.Header;
import ai.chat2db.community.domain.api.model.result.ResultCell;
import ai.chat2db.community.domain.api.model.sql.SqlExecuteRequest;
import ai.chat2db.community.domain.api.model.sql.extension.SqlExecutionContext;
import ai.chat2db.community.domain.api.model.sql.extension.SqlExecutionOperation;
import ai.chat2db.community.domain.api.model.sql.extension.SqlExecutionPlan;
import ai.chat2db.community.domain.api.model.sql.extension.SqlResultColumnContext;
import ai.chat2db.community.domain.api.service.db.ISqlExecutionResultConsumer;
import ai.chat2db.community.domain.api.service.db.extension.ISqlExecutionPolicy;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqlExecutionPolicyManagerTest {

    @Test
    void eachPlanHasAStableUniqueExecutionId() {
        SqlExecutionPolicyManager manager = new SqlExecutionPolicyManager(List.of());

        SqlExecutionPlan first = manager.plan(context(SqlExecutionOperation.EXECUTE));
        SqlExecutionPlan second = manager.plan(context(SqlExecutionOperation.EXECUTE));

        assertNotNull(first.getExecutionId());
        assertEquals(first.getExecutionId(), first.getExecutionId());
        assertNotEquals(first.getExecutionId(), second.getExecutionId());
    }

    @Test
    void checkpointInvokesEveryPolicyInOrder() {
        List<String> calls = new ArrayList<>();
        ISqlExecutionPolicy first = new ISqlExecutionPolicy() {
            @Override
            public void checkpoint(SqlExecutionPlan plan) {
                calls.add("first:" + plan.getExecutionId());
            }
        };
        ISqlExecutionPolicy second = new ISqlExecutionPolicy() {
            @Override
            public void checkpoint(SqlExecutionPlan plan) {
                calls.add("second:" + plan.getExecutionId());
            }
        };
        SqlExecutionPolicyManager manager = new SqlExecutionPolicyManager(List.of(first, second));
        SqlExecutionPlan plan = manager.plan(context(SqlExecutionOperation.EXPORT));

        manager.checkpoint(plan);

        assertEquals(List.of("first:" + plan.getExecutionId(), "second:" + plan.getExecutionId()), calls);
    }

    @Test
    void planPreservesTheExecutionIdOwnedByTheOuterSqlJob() {
        SqlExecutionPolicyManager manager = new SqlExecutionPolicyManager(List.of());

        SqlExecutionPlan plan = manager.plan(context(SqlExecutionOperation.EXECUTE), "sql-job-42");

        assertEquals("sql-job-42", plan.getExecutionId());
    }

    @Test
    void policiesRewriteInOrderAndUseTheStrictestRowLimit() {
        List<String> observedSql = new ArrayList<>();
        ISqlExecutionPolicy first = new ISqlExecutionPolicy() {
            @Override
            public String rewriteSql(SqlExecutionContext context, String sql) {
                observedSql.add(sql);
                return "select * from (" + sql + ") first_policy";
            }

            @Override
            public Integer maxRows(SqlExecutionContext context, String sql) {
                return 100;
            }
        };
        ISqlExecutionPolicy second = new ISqlExecutionPolicy() {
            @Override
            public String rewriteSql(SqlExecutionContext context, String sql) {
                observedSql.add(sql);
                return sql + " where tenant_id = 7";
            }

            @Override
            public Integer maxRows(SqlExecutionContext context, String sql) {
                return 20;
            }
        };
        SqlExecutionPolicyManager manager = new SqlExecutionPolicyManager(List.of(first, second));

        SqlExecutionPlan plan = manager.plan(context(SqlExecutionOperation.EXECUTE));

        assertEquals(List.of("select * from orders", "select * from (select * from orders) first_policy"),
                observedSql);
        assertEquals("select * from (select * from orders) first_policy where tenant_id = 7", plan.getSql());
        assertEquals(20, plan.getMaxRows());
        assertTrue(manager.isRowAllowed(plan, 19));
        assertFalse(manager.isRowAllowed(plan, 20));
    }

    @Test
    void maxRowsTightensSqlExecutionRequestWithoutChangingSmallerPage() {
        SqlExecutionPolicyManager manager = new SqlExecutionPolicyManager(List.of(maxRowsPolicy(20)));
        SqlExecutionPlan plan = manager.plan(context(SqlExecutionOperation.EXECUTE));
        SqlExecuteRequest request = new SqlExecuteRequest();
        request.setPageSize(100);
        request.setPageSizeAll(true);

        manager.applyMaxRows(request, plan);

        assertEquals(20, request.getPageSize());
        assertFalse(request.getPageSizeAll());

        request.setPageSize(10);
        manager.applyMaxRows(request, plan);
        assertEquals(10, request.getPageSize());
    }

    @Test
    void resultColumnPolicyFiltersHeadersAndCellsAndDisablesEditing() {
        ISqlExecutionPolicy policy = new ISqlExecutionPolicy() {
            @Override
            public boolean includeColumn(SqlResultColumnContext context) {
                return !"secret".equals(context.getColumnName());
            }
        };
        SqlExecutionPolicyManager manager = new SqlExecutionPolicyManager(List.of(policy));
        SqlExecutionPlan plan = manager.plan(context(SqlExecutionOperation.EXECUTE));
        Header rowNumber = Header.builder().name("#").dataType(DataTypeEnum.CHAT2DB_ROW_NUMBER.getCode()).build();
        Header id = Header.builder().name("id").columnName("id").build();
        Header secret = Header.builder().name("secret").columnName("secret").build();
        ExecuteResponse result = new ExecuteResponse();
        result.setCanEdit(true);
        result.setHeaderList(new ArrayList<>(List.of(rowNumber, id, secret)));
        result.setDataList(new ArrayList<>(List.of(new ArrayList<>(List.of(
                ResultCell.of("1"), ResultCell.of("7"), ResultCell.of("hidden"))))));

        manager.filterResultColumns(plan, List.of(result));

        assertEquals(List.of(rowNumber, id), result.getHeaderList());
        assertEquals(List.of("1", "7"), result.getDisplayDataList().get(0));
        assertFalse(result.isCanEdit());
    }

    @Test
    void resultEditingRequiresEveryPolicyEvenWhenColumnsRemainVisible() {
        ISqlExecutionPolicy policy = new ISqlExecutionPolicy() {
            @Override
            public boolean canEditResult(SqlExecutionPlan plan, List<SqlResultColumnContext> columns) {
                assertEquals(List.of("id"), columns.stream()
                        .filter(column -> !column.isSynthetic())
                        .map(SqlResultColumnContext::getColumnName)
                        .toList());
                return false;
            }
        };
        SqlExecutionPolicyManager manager = new SqlExecutionPolicyManager(List.of(policy));
        SqlExecutionPlan plan = manager.plan(context(SqlExecutionOperation.EXECUTE));
        ExecuteResponse result = editableResult();

        manager.filterResultColumns(plan, List.of(result));

        assertEquals(2, result.getHeaderList().size());
        assertFalse(result.isCanEdit());
    }

    @Test
    void streamingResultUsesTheSameEditingPolicyBeforeItReachesTheDelegate() {
        ISqlExecutionPolicy policy = new ISqlExecutionPolicy() {
            @Override
            public boolean canEditResult(SqlExecutionPlan plan, List<SqlResultColumnContext> columns) {
                return false;
            }
        };
        SqlExecutionPolicyManager manager = new SqlExecutionPolicyManager(List.of(policy));
        SqlExecutionPlan plan = manager.plan(context(SqlExecutionOperation.EXECUTE));
        List<Boolean> observedCanEdit = new ArrayList<>();
        ISqlExecutionResultConsumer consumer = manager.wrapStreamingConsumer(plan,
                recordingConsumer(observedCanEdit));
        ExecuteResponse result = editableResult();

        consumer.resultStarted(result);
        consumer.resultFinished(result);

        assertEquals(List.of(false, false), observedCanEdit);
        assertFalse(result.isCanEdit());
    }

    @Test
    void resultRowsAndCountsAreCappedEvenWhenTheExecutorIgnoresTheRequestedLimit() {
        SqlExecutionPolicyManager manager = new SqlExecutionPolicyManager(List.of(maxRowsPolicy(2)));
        SqlExecutionPlan plan = manager.plan(context(SqlExecutionOperation.EXECUTE));
        ExecuteResponse result = new ExecuteResponse();
        result.setHeaderList(List.of(Header.builder().name("id").columnName("id").build()));
        result.setDataList(new ArrayList<>(List.of(
                List.of(ResultCell.of("1")),
                List.of(ResultCell.of("2")),
                List.of(ResultCell.of("3")))));
        result.setHasNextPage(true);
        result.setFuzzyTotal("3+");

        manager.filterResultColumns(plan, List.of(result));

        assertEquals(List.of("1", "2"), result.getDisplayDataList().stream().map(row -> row.get(0)).toList());
        assertFalse(result.getHasNextPage());
        assertEquals("2", result.getFuzzyTotal());
        assertEquals(2L, manager.limitCount(plan, 9L));
    }

    @Test
    void emptyPolicyListIsAnExactNoOp() {
        SqlExecutionPolicyManager manager = new SqlExecutionPolicyManager(List.of());
        SqlExecutionContext context = context(SqlExecutionOperation.EXPORT);
        SqlExecutionPlan plan = manager.plan(context);
        SqlExecuteRequest request = new SqlExecuteRequest();
        request.setPageSize(42);
        List<ExecuteResponse> results = new ArrayList<>();

        manager.applyMaxRows(request, plan);

        assertSame(context, plan.getContext());
        assertEquals(context.getOriginalSql(), plan.getSql());
        assertEquals(42, request.getPageSize());
        assertSame(results, manager.filterResultColumns(plan, results));
        assertTrue(manager.isRowAllowed(plan, Integer.MAX_VALUE));
    }

    @Test
    void invalidPolicyOutputFailsClosed() {
        SqlExecutionPolicyManager invalidRows = new SqlExecutionPolicyManager(List.of(maxRowsPolicy(0)));
        SqlExecutionPolicyManager blankSql = new SqlExecutionPolicyManager(List.of(new ISqlExecutionPolicy() {
            @Override
            public String rewriteSql(SqlExecutionContext context, String sql) {
                return " ";
            }
        }));

        assertThrows(IllegalStateException.class,
                () -> invalidRows.plan(context(SqlExecutionOperation.EXECUTE)));
        assertThrows(IllegalStateException.class,
                () -> blankSql.plan(context(SqlExecutionOperation.EXECUTE)));
    }

    private static ISqlExecutionPolicy maxRowsPolicy(int maxRows) {
        return new ISqlExecutionPolicy() {
            @Override
            public Integer maxRows(SqlExecutionContext context, String sql) {
                return maxRows;
            }
        };
    }

    private static ExecuteResponse editableResult() {
        Header rowNumber = Header.builder().name("#")
                .dataType(DataTypeEnum.CHAT2DB_ROW_NUMBER.getCode()).build();
        Header id = Header.builder().name("id").columnName("id")
                .databaseName("shop").tableName("orders").build();
        ExecuteResponse result = new ExecuteResponse();
        result.setCanEdit(true);
        result.setHeaderList(new ArrayList<>(List.of(rowNumber, id)));
        result.setDataList(new ArrayList<>());
        return result;
    }

    private static ISqlExecutionResultConsumer recordingConsumer(List<Boolean> observedCanEdit) {
        return new ISqlExecutionResultConsumer() {
            @Override
            public void statementStarted(String sql, String originalSql, String comment) {
            }

            @Override
            public void resultStarted(ExecuteResponse result) {
                observedCanEdit.add(result.isCanEdit());
            }

            @Override
            public void rows(ExecuteResponse result, List<List<ResultCell>> rows) {
            }

            @Override
            public void resultFinished(ExecuteResponse result) {
                observedCanEdit.add(result.isCanEdit());
            }

            @Override
            public void updateCount(ExecuteResponse result) {
            }

            @Override
            public void statementFinished(String sql, long duration) {
            }
        };
    }

    private static SqlExecutionContext context(SqlExecutionOperation operation) {
        return new SqlExecutionContext(7L, "MYSQL", "shop", null, "orders",
                "select * from orders", operation, operation == SqlExecutionOperation.EXPORT ? "csv" : null);
    }
}

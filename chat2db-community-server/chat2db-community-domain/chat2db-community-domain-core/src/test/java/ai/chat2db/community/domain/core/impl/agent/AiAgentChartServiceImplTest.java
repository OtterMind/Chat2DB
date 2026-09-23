package ai.chat2db.community.domain.core.impl.agent;

import ai.chat2db.community.domain.api.model.agent.chart.AiAgentChart;
import ai.chat2db.community.domain.api.model.agent.chart.DbAgentQueryResult;
import ai.chat2db.community.domain.api.model.agent.tool.AgentToolExecutionContext;
import ai.chat2db.community.domain.api.model.request.agent.AiAgentChartRenderRequest;
import ai.chat2db.community.domain.api.model.request.agent.AiAgentChartRenderRequest.AiAgentChartSeriesRequest;
import ai.chat2db.community.domain.api.model.response.agent.DbAgentDatabaseResponse;
import ai.chat2db.community.domain.api.model.response.agent.DbAgentDatabaseResponse.Page;
import ai.chat2db.community.domain.api.model.response.agent.DbAgentDatabaseResponse.QueryColumn;
import ai.chat2db.community.domain.api.model.response.agent.DbAgentDatabaseResponse.QueryData;
import ai.chat2db.community.domain.api.model.response.agent.DbAgentDatabaseResponse.Scope;
import ai.chat2db.community.domain.api.model.response.agent.DbAgentDatabaseResponse.SqlExecutionData;
import ai.chat2db.community.domain.api.model.response.agent.DbAgentDatabaseResponse.SqlResult;
import ai.chat2db.community.domain.api.service.agent.IAgentQueryResultStorage;
import ai.chat2db.community.tools.enums.agent.AgentEventType;
import ai.chat2db.community.tools.exception.agent.AgentChartException;
import ai.chat2db.community.tools.model.agent.runtime.AgentRuntimeEvent;
import com.alibaba.fastjson2.JSON;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AiAgentChartServiceImplTest {
    private final Map<String, DbAgentQueryResult> saved = new HashMap<>();
    private final List<AgentRuntimeEvent> events = new ArrayList<>();
    private final AtomicBoolean active = new AtomicBoolean(true);
    private final AgentToolExecutionContext context = new AgentToolExecutionContext("session", "run", "call", 1L, events::add, active::get);
    private final AiAgentChartServiceImpl service = new AiAgentChartServiceImpl(new IAgentQueryResultStorage() {
        @Override public void create(DbAgentQueryResult result, Long userId) { saved.put(result.id(), result); }
        @Override public DbAgentQueryResult get(String sessionId, String resultId, Long userId) {
            DbAgentQueryResult result = saved.get(resultId);
            return result != null && result.sessionId().equals(sessionId) && userId == 1L ? result : null;
        }
    });

    @Test
    void referencesEachStatementAndRendersOriginalValuesWithoutExecutingSqlAgain() {
        var first = result(List.of(List.of("Jan", "10.25"), Arrays.asList("Feb", null)), false);
        var second = new SqlResult(2, "SELECT ...", true, first.data(), first.page(), null);
        var response = service.captureQueryResults(DbAgentDatabaseResponse.success(new Scope("1", "MYSQL", "db", null),
                new SqlExecutionData(List.of(first, second), 2, true), first.page(), null, List.of()), context);
        assertNotEquals(response.data().results().get(0).resultId(), response.data().results().get(1).resultId());
        String id = response.data().results().get(0).resultId();
        var chart = service.render(request(id, "Line", "month", "amount"), context);
        assertEquals(new BigDecimal("10.25"), chart.data().get(0).get("amount"));
        assertNull(chart.data().get(1).get("amount"));
        assertEquals("10.25", saved.get(id).data().rows().get(0).get(1));
        assertEquals(AgentEventType.CHART_CREATED, events.get(0).type());
        assertEquals(chart, events.get(0).payload().get("chart"));
        assertTrue(JSON.toJSONString(events.get(0)).contains("\"amount\":null"), "Persist SQL NULL in the chart event");
    }

    @Test
    void largeUnselectedColumnsRemainSavedAndDoNotPreventAChart() {
        var body = "大字段".repeat(200000);
        var data = new QueryData(List.of(new QueryColumn("month", "VARCHAR"), new QueryColumn("amount", "DECIMAL"),
                new QueryColumn("body", "TEXT")), List.of(List.of("Jan", "12.30", body)), "database-text", 1L, List.of(), null);
        var result = new SqlResult(1, "SELECT ...", true, data, new Page(1, 50, 1, null, false, null), null);
        var response = service.captureQueryResults(DbAgentDatabaseResponse.success(new Scope("1", "MYSQL", "db", null),
                new SqlExecutionData(List.of(result), 1, true), result.page(), null, List.of()), context);
        String id = response.data().results().get(0).resultId();
        assertEquals(body, saved.get(id).data().rows().get(0).get(2));
        assertEquals(new BigDecimal("12.30"), service.render(request(id, "Column", "month", "amount"), context).data().get(0).get("amount"));
    }

    @Test
    void storageFailureDoesNotRewriteAnAlreadyExecutedSqlOutcome() {
        var failing = new AiAgentChartServiceImpl(new IAgentQueryResultStorage() {
            @Override public void create(DbAgentQueryResult result, Long userId) { throw new IllegalStateException("disk full"); }
            @Override public DbAgentQueryResult get(String sessionId, String resultId, Long userId) { return null; }
        });
        var result = result(List.of(List.of("Jan", "5")), false);
        var response = failing.captureQueryResults(DbAgentDatabaseResponse.success(new Scope("1", "MYSQL", "db", null),
                new SqlExecutionData(List.of(result), 1, true), result.page(), null, List.of()), context);
        assertTrue(response.ok());
        assertNull(response.data().results().get(0).resultId());
        assertEquals(result.data(), response.data().results().get(0).data());
        assertTrue(response.warnings().get(0).contains("disk full"));
    }

    @Test
    void rejectsUnknownResultsWrongFieldsAndNonNumericMetricsWithoutCreatingAChart() {
        assertCode("RESULT_NOT_FOUND", () -> service.render(request("unknown", "Line", "month", "amount"), context));
        String id = capture(List.of(List.of("Jan", "text")), false);
        assertCode("FIELD_NOT_FOUND", () -> service.render(request(id, "Line", "missing", "amount"), context));
        assertCode("NON_NUMERIC_FIELD", () -> service.render(request(id, "Line", "month", "amount"), context));
        assertCode("INVALID_CHART_TYPE", () -> service.render(request(id, "Unknown", "month", "amount"), context));
        assertTrue(events.isEmpty());
    }

    @Test
    void keepsBatchFailureWhileReferencingTheSuccessfulStatement() {
        var first = result(List.of(List.of("Jan", "1")), false);
        var error = new DbAgentDatabaseResponse.Error("SQL_ERROR", "sql", "second statement failed");
        var failed = new SqlResult(2, "invalid SQL", false, null, null, error);
        var original = new DbAgentDatabaseResponse<>(false, new Scope("1", "MYSQL", "db", null),
                new SqlExecutionData(List.of(first, failed), 2, false), null, error, null, List.<String>of());
        var captured = service.captureQueryResults(original, context);
        assertFalse(captured.ok());
        assertEquals(error, captured.error());
        assertNotNull(captured.data().results().get(0).resultId());
        assertNull(captured.data().results().get(1).resultId());
        assertEquals(1, saved.size());
    }

    @Test
    void rejectsPrecisionLossEmptyResultsAndMisleadingSingleValueCharts() {
        String precise = capture(List.of(List.of("Jan", "9007199254740993")), false);
        assertCode("NUMERIC_PRECISION", () -> service.render(request(precise, "Column", "month", "amount"), context));
        String empty = capture(List.of(), false);
        assertCode("NO_DATA", () -> service.render(request(empty, "Column", "month", "amount"), context));
        String multiple = capture(List.of(List.of("Jan", "1"), List.of("Feb", "2")), false);
        assertCode("EXPECTED_SINGLE_ROW", () -> service.render(request(multiple, "Statistics", null, "amount"), context));
        assertTrue(events.isEmpty());
    }

    @Test
    void supportsPieScatterStatisticsAndComboAndRetainsPagination() {
        String id = capture(List.of(List.of("1", "2.5")), true);
        for (String type : List.of("Column", "Bar", "Line", "AreaLine", "Pie", "RingPie", "RosePie", "Funnel", "Scatter", "Statistics")) {
            var chart = service.render(request(id, type, type.equals("Statistics") ? null : "month", "amount"), context);
            assertEquals(type, chart.chartType());
            assertTrue(chart.page().hasMore());
        }
        var combo = service.render(new AiAgentChartRenderRequest(id, "Combo", "month", null, "Combo",
                List.of(new AiAgentChartSeriesRequest("amount", "Line", "right")), List.of(), false), context);
        assertEquals("amount", combo.series().get(0).field());
        assertEquals("right", combo.series().get(0).axisPosition());
    }

    @Test
    void doesNotRenderAfterCancellationOrAcrossSessions() {
        String id = capture(List.of(List.of("Jan", "3")), false);
        var foreign = new AgentToolExecutionContext("other-session", "run", "call", 1L, events::add, () -> true);
        assertCode("RESULT_NOT_FOUND", () -> service.render(request(id, "Line", "month", "amount"), foreign));
        active.set(false);
        assertCode("RUN_CANCELLED", () -> service.render(request(id, "Line", "month", "amount"), context));
        assertTrue(events.isEmpty());
    }

    @Test
    void groupedChartsPreserveMultipleDimensionsNullEmptyAndSpecialLabels() throws Exception {
        var rows = List.of(
                Arrays.asList("Jan", "1.25", null, "retail"),
                List.of("Jan", "2", "", "retail"),
                List.of("Jan", "3", "null", "retail"),
                List.of("Jan", "4", "a|b", "c"),
                List.of("Jan", "5", "a", "b|c"),
                List.of("Jan", "6", "华东\"\\\n", "零售"));
        String id = capture(List.of("month", "amount", "region", "channel"), rows, true, List.of("Partial query page"));
        for (String type : List.of("Column", "Bar", "Line", "AreaLine", "Combo")) {
            var series = type.equals("Combo") ? List.of(new AiAgentChartSeriesRequest("amount", "Column", "left")) : null;
            var chart = service.render(new AiAgentChartRenderRequest(id, type, "month", series == null ? "amount" : null,
                    "By region and channel", series, List.of("region", "channel"), false), context);
            assertEquals(List.of("region", "channel"), chart.groupBy());
            assertFalse(chart.stack());
            assertEquals(6, chart.data().size());
            assertTrue(chart.data().get(0).containsKey("region"));
            assertNull(chart.data().get(0).get("region"));
            for (int i = 1; i < rows.size(); i++) assertEquals(rows.get(i).get(2), chart.data().get(i).get("region"));
            assertEquals(new BigDecimal("1.25"), chart.data().get(0).get("amount"));
            assertEquals(List.of("Partial query page"), chart.warnings());
            assertTrue(chart.page().hasMore());
            String persisted = JSON.toJSONString(chart);
            assertTrue(persisted.contains("\"region\":null"));
            var restored = JSON.parseObject(persisted, AiAgentChart.class);
            assertEquals(chart.groupBy(), restored.groupBy());
            assertEquals(chart.stack(), restored.stack());
            for (int i = 0; i < rows.size(); i++) assertEquals(rows.get(i).get(2), restored.data().get(i).get("region"));
            assertEquals(chart.groupBy(), new ObjectMapper().readValue(persisted, AiAgentChart.class).groupBy());
        }
    }

    @Test
    void rejectsDuplicateCategoryGrainWithoutChangingLegacyCharts() {
        String id = capture(List.of("month", "amount", "region"),
                List.of(List.of("Jan", "1", "East"), List.of("Jan", "2", "East")), false, List.of());
        var error = assertThrows(AgentChartException.class, () -> service.render(
                new AiAgentChartRenderRequest(id, "Column", "month", "amount", null, null, List.of("region"), false), context));
        assertEquals("DUPLICATE_CATEGORY", error.code());
        assertTrue(error.getMessage().contains("SQL GROUP BY"));
        assertCode("DUPLICATE_CATEGORY", () -> service.render(
                new AiAgentChartRenderRequest(id, "Column", "month", "amount", null, null, List.of(), true), context));
        assertTrue(events.isEmpty());
        assertEquals(2, service.render(request(id, "Column", "month", "amount"), context).data().size());
    }

    @Test
    void groupedScatterRetainsMultiplePointsWithTheSameXAndGroup() {
        String id = capture(List.of("month", "amount", "region"),
                List.of(List.of("1", "2", "East"), List.of("1", "3", "East")), false, List.of());
        var chart = service.render(new AiAgentChartRenderRequest(id, "Scatter", "month", "amount", null, null,
                List.of("region"), false), context);
        assertEquals(2, chart.data().size());
        assertEquals(BigDecimal.ONE, chart.data().get(0).get("month"));
        assertEquals(new BigDecimal("3"), chart.data().get(1).get("amount"));
    }

    @Test
    void rejectsUnsupportedGroupingAndStackingAndFieldConflicts() {
        String id = capture(List.of("month", "amount", "region"), List.of(List.of("Jan", "1", "East")), false, List.of());
        for (String type : List.of("Pie", "RingPie", "RosePie", "Funnel", "Statistics")) {
            assertCode("UNSUPPORTED_GROUPING", () -> service.render(new AiAgentChartRenderRequest(id, type,
                    "month", "amount", null, null, List.of("region"), false), context));
        }
        for (String type : List.of("Line", "Pie", "RingPie", "RosePie", "Funnel", "Scatter", "Statistics")) {
            assertCode("UNSUPPORTED_STACK", () -> service.render(new AiAgentChartRenderRequest(id, type,
                    "month", "amount", null, null, List.of(), true), context));
        }
        for (List<String> groups : List.of(List.of("month"), List.of("amount"), List.of("region", "region"))) {
            assertCode("DUPLICATE_FIELD", () -> service.render(new AiAgentChartRenderRequest(id, "Column",
                    "month", "amount", null, null, groups, false), context));
        }
        assertCode("DUPLICATE_FIELD", () -> service.render(new AiAgentChartRenderRequest(id, "Combo", "month", null, null,
                List.of(new AiAgentChartSeriesRequest("amount", "Column", "left")), List.of("amount"), false), context));
        assertCode("FIELD_NOT_FOUND", () -> service.render(new AiAgentChartRenderRequest(id, "Column", "month", "amount", null,
                null, List.of("missing"), false), context));
        assertTrue(events.isEmpty());
    }

    @Test
    void stacksSupportedChartsAndComboMetricsOnTheirDeclaredAxes() {
        String id = capture(List.of("month", "amount", "orders", "region"),
                List.of(List.of("Jan", "100", "5", "East"), List.of("Jan", "200", "10", "West")), false, List.of());
        for (String type : List.of("Column", "Bar", "AreaLine")) {
            assertTrue(service.render(new AiAgentChartRenderRequest(id, type, "month", "amount", null,
                    null, List.of("region"), true), context).stack());
        }
        var metrics = List.of(new AiAgentChartSeriesRequest("amount", "Column", "left"),
                new AiAgentChartSeriesRequest("orders", "Line", "right"));
        var chart = service.render(new AiAgentChartRenderRequest(id, "Combo", "month", null, null, metrics,
                List.of("region"), true), context);
        assertTrue(chart.stack());
        assertEquals("left", chart.series().get(0).axisPosition());
        assertEquals("right", chart.series().get(1).axisPosition());
        assertEquals("Line", chart.series().get(1).chartType());
        assertCode("UNSUPPORTED_STACK", () -> service.render(new AiAgentChartRenderRequest(id, "Combo", "month", null, null,
                List.of(new AiAgentChartSeriesRequest("amount", "Line", "left"),
                        new AiAgentChartSeriesRequest("orders", "Scatter", "right")), List.of("region"), true), context));
    }

    @Test
    void enforcesDerivedSeriesLimitWithoutTruncation() {
        List<List<String>> rows = new ArrayList<>();
        for (int i = 0; i < 33; i++) rows.add(List.of("Jan", "10", "5", "Region " + i));
        var columns = List.of("month", "amount", "orders", "region");
        var metrics = List.of(new AiAgentChartSeriesRequest("amount", "Column", "left"),
                new AiAgentChartSeriesRequest("orders", "Line", "right"));
        String atLimit = capture(columns, rows.subList(0, 16), false, List.of());
        assertEquals(16, service.render(new AiAgentChartRenderRequest(atLimit, "Combo", "month", null, null,
                metrics, List.of("region"), false), context).data().size());
        String overCombo = capture(columns, rows.subList(0, 17), false, List.of());
        assertCode("TOO_MANY_SERIES", () -> service.render(new AiAgentChartRenderRequest(overCombo, "Combo", "month", null, null,
                metrics, List.of("region"), false), context));
        String overSingle = capture(columns, rows, false, List.of());
        assertCode("TOO_MANY_SERIES", () -> service.render(new AiAgentChartRenderRequest(overSingle, "Column", "month", "amount", null,
                null, List.of("region"), false), context));
        assertEquals(1, events.size(), "Only the valid chart is saved; oversized charts do not emit partial charts");
    }

    @Test
    void groupedChartsRetainCancellationOwnershipAndPrecisionChecks() {
        String id = capture(List.of("month", "amount", "region"),
                List.of(List.of("Jan", "9007199254740993", "East")), false, List.of());
        var request = new AiAgentChartRenderRequest(id, "Column", "month", "amount", null, null, List.of("region"), true);
        assertCode("NUMERIC_PRECISION", () -> service.render(request, context));
        var otherUser = new AgentToolExecutionContext("session", "run", "call", 2L, events::add, () -> true);
        assertCode("RESULT_NOT_FOUND", () -> service.render(request, otherUser));
        var otherSession = new AgentToolExecutionContext("other", "run", "call", 1L, events::add, () -> true);
        assertCode("RESULT_NOT_FOUND", () -> service.render(request, otherSession));
        active.set(false);
        assertCode("RUN_CANCELLED", () -> service.render(request, context));
        assertTrue(events.isEmpty());
    }

    private String capture(List<String> columns, List<List<String>> rows, boolean hasMore, List<String> warnings) {
        var data = new QueryData(columns.stream().map(name -> new QueryColumn(name, "VARCHAR")).toList(),
                rows, "database-text", 1L, List.of(), null);
        var result = new SqlResult(1, "SELECT ...", true, data,
                new Page(1, 50, rows.size(), null, hasMore, hasMore ? 2 : null), null);
        return service.captureQueryResults(DbAgentDatabaseResponse.success(new Scope("1", "MYSQL", "db", null),
                new SqlExecutionData(List.of(result), 1, true), result.page(), null, warnings), context)
                .data().results().get(0).resultId();
    }

    private String capture(List<List<String>> rows, boolean hasMore) {
        var result = result(rows, hasMore);
        return service.captureQueryResults(DbAgentDatabaseResponse.success(new Scope("1", "MYSQL", "db", null),
                new SqlExecutionData(List.of(result), 1, true), result.page(), null, List.of()), context)
                .data().results().get(0).resultId();
    }

    private SqlResult result(List<List<String>> rows, boolean hasMore) {
        var data = new QueryData(List.of(new QueryColumn("month", "VARCHAR"), new QueryColumn("amount", "DECIMAL")),
                rows, "database-text", 1L, List.of(), null);
        return new SqlResult(1, "SELECT ...", true, data, new Page(1, 50, rows.size(), null, hasMore, hasMore ? 2 : null), null);
    }

    private AiAgentChartRenderRequest request(String id, String type, String x, String y) {
        return new AiAgentChartRenderRequest(id, type, x, y, "Monthly totals", null, List.of(), false);
    }

    private void assertCode(String expected, Runnable action) {
        assertEquals(expected, assertThrows(AgentChartException.class, action::run).code());
    }
}

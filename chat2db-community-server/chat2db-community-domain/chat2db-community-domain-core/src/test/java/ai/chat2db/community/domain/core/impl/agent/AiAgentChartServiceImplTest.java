package ai.chat2db.community.domain.core.impl.agent;

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
                List.of(new AiAgentChartSeriesRequest("amount", "Line", "right"))), context);
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
        return new AiAgentChartRenderRequest(id, type, x, y, "Monthly totals", null);
    }

    private void assertCode(String expected, Runnable action) {
        assertEquals(expected, assertThrows(AgentChartException.class, action::run).code());
    }
}

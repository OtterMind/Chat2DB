package ai.chat2db.community.domain.core.converter.agent;

import ai.chat2db.community.domain.api.model.agent.chart.AiAgentChart;
import ai.chat2db.community.domain.api.model.agent.chart.DbAgentQueryResult;
import ai.chat2db.community.domain.api.model.agent.tool.AgentToolExecutionContext;
import ai.chat2db.community.domain.api.model.request.agent.AiAgentChartRenderRequest;
import ai.chat2db.community.domain.api.model.response.agent.DbAgentDatabaseResponse.SqlExecutionData;
import ai.chat2db.community.domain.api.model.response.agent.DbAgentDatabaseResponse.SqlResult;
import ai.chat2db.community.domain.api.model.response.agent.DbAgentDatabaseResponse;
import java.util.List;
import java.util.Map;

public final class AgentChartConverter {
    private AgentChartConverter() { }

    public static DbAgentQueryResult query2snapshot(String id, SqlResult result,
            DbAgentDatabaseResponse<SqlExecutionData> response, AgentToolExecutionContext context) {
        return new DbAgentQueryResult(id, context.sessionId(), context.runId(), result.sql(),
                response.scope(), result.data(), result.page(), response.warnings());
    }

    public static SqlResult result2reference(SqlResult result, String id) {
        return new SqlResult(result.statementIndex(), result.sql(), result.success(), result.data(),
                result.page(), result.error(), id);
    }

    public static DbAgentDatabaseResponse<SqlExecutionData> results2response(
            DbAgentDatabaseResponse<SqlExecutionData> response, List<SqlResult> results) {
        return new DbAgentDatabaseResponse<>(response.ok(), response.scope(),
                new SqlExecutionData(List.copyOf(results), response.data().statementCount(), response.data().readOnly()),
                response.page(), response.error(), response.nextAction(), response.warnings());
    }

    public static AiAgentChart request2chart(String id, AiAgentChartRenderRequest request,
            DbAgentQueryResult source, AgentToolExecutionContext context, List<Map<String, Object>> data) {
        List<AiAgentChart.Series> series = request.series() == null ? List.of() : request.series().stream()
                .map(item -> new AiAgentChart.Series(item.field(), item.chartType(), item.axisPosition())).toList();
        String title = request.title() == null || request.title().isBlank()
                ? (request.yField() == null ? request.chartType() : request.yField()) : request.title().trim();
        return new AiAgentChart(id, context.runId(), source.id(), request.chartType(), title,
                request.xField(), request.yField(), series, data, source.page(), source.warnings());
    }
}

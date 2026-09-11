package ai.chat2db.community.domain.api.service.agent;

import ai.chat2db.community.domain.api.model.agent.chart.AiAgentChart;
import ai.chat2db.community.domain.api.model.agent.tool.AgentToolExecutionContext;
import ai.chat2db.community.domain.api.model.request.agent.AiAgentChartRenderRequest;
import ai.chat2db.community.domain.api.model.response.agent.DbAgentDatabaseResponse.SqlExecutionData;
import ai.chat2db.community.domain.api.model.response.agent.DbAgentDatabaseResponse;

public interface IAiAgentChartService {
    DbAgentDatabaseResponse<SqlExecutionData> captureQueryResults(
            DbAgentDatabaseResponse<SqlExecutionData> response, AgentToolExecutionContext context);

    AiAgentChart render(AiAgentChartRenderRequest aiAgentChartRenderRequest, AgentToolExecutionContext context);
}

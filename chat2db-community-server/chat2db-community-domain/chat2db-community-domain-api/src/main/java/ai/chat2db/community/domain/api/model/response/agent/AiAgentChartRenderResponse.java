package ai.chat2db.community.domain.api.model.response.agent;

import ai.chat2db.community.tools.agent.tool.IAgentToolResult;
import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record AiAgentChartRenderResponse(boolean ok, RenderedChart data, Error error)
        implements IAgentToolResult<AiAgentChartRenderResponse.RenderedChart> {
    public record RenderedChart(String chartId, String resultId, String chartType, String title,
            int rowCount, boolean partial) { }
    public record Error(String code, String field, String message) { }

    public static AiAgentChartRenderResponse failure(String code, String field, String message) {
        return new AiAgentChartRenderResponse(false, null, new Error(code, field, message));
    }
}

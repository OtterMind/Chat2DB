package ai.chat2db.community.web.api.adapter.agent;

import ai.chat2db.community.domain.api.enums.agent.AiAgentChartType;
import ai.chat2db.community.domain.api.model.agent.tool.AgentToolExecutionContext;
import ai.chat2db.community.domain.api.model.request.agent.AiAgentChartRenderRequest;
import ai.chat2db.community.domain.api.model.response.agent.AiAgentChartRenderResponse;
import ai.chat2db.community.domain.api.service.agent.IAiAgentChartService;
import ai.chat2db.community.tools.exception.agent.AgentChartException;
import ai.chat2db.community.tools.model.agent.runtime.AgentToolAccess;
import ai.chat2db.community.web.api.converter.agent.AgentChartToolConverter;
import jakarta.validation.Validator;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class AgentChartTool {
    public static final String NAME = "render_chart";
    private final IAiAgentChartService charts;
    private final AgentChartToolConverter converter;
    private final Validator validator;

    public AgentChartTool(IAiAgentChartService charts, AgentChartToolConverter converter, Validator validator) {
        this.charts = charts;
        this.converter = converter;
        this.validator = validator;
    }

    public AgentToolAccess.Tool definition() {
        var field = Map.of("type", "string", "minLength", 1, "maxLength", 256);
        var series = Map.of("type", "object", "properties", Map.of(
                "field", field, "chartType", Map.of("type", "string", "enum", List.of("Column", "Line", "AreaLine", "Scatter")),
                "axisPosition", Map.of("type", "string", "enum", List.of("left", "right"))),
                "required", List.of("field", "chartType", "axisPosition"), "additionalProperties", false);
        var schema = Map.<String, Object>of("type", "object", "properties", Map.of(
                "resultId", Map.of("type", "string", "pattern", "^[a-zA-Z0-9][a-zA-Z0-9_-]{0,99}$", "description", "Copy resultId from the desired statement in db_query data.results. The result must belong to this conversation."),
                "chartType", Map.of("type", "string", "enum", AiAgentChartType.codes()),
                "xField", Map.of("type", "string", "maxLength", 256, "description", "Exact category or X column. Required except for Statistics. For pie charts this is the category."),
                "yField", Map.of("type", "string", "maxLength", 256, "description", "Exact numeric metric column. Required except for Combo. Statistics requires a one-row query result."),
                "title", Map.of("type", "string", "maxLength", 200),
                "series", Map.of("type", "array", "minItems", 1, "maxItems", 8, "items", series, "description", "Only supported for Combo. Omit for every other chart type. Use distinct numeric metric columns.")),
                "required", List.of("resultId", "chartType"), "additionalProperties", false);
        return new AgentToolAccess.Tool(NAME,
                "Render a chart from a saved db_query result. This tool uses the actual query values and never executes SQL. Choose fields and chart type; do not supply or rewrite data. The chart is displayed and saved in the conversation. Partial query pages are labelled as partial. Errors describe how to correct the request.",
                schema, "Display and save a chart using a db_query resultId.", List.of());
    }

    public AiAgentChartRenderResponse execute(Map<String, Object> arguments, AgentToolExecutionContext context) {
        AiAgentChartRenderRequest request;
        try {
            request = converter.arguments2request(arguments);
        } catch (IllegalArgumentException error) {
            return AiAgentChartRenderResponse.failure("INVALID_ARGUMENT", null,
                    "Use only resultId, chartType, xField, yField, title and series with their declared types. Data must come from db_query.");
        }
        var violations = validator.validate(request);
        if (!violations.isEmpty()) {
            var violation = violations.iterator().next();
            return AiAgentChartRenderResponse.failure("INVALID_ARGUMENT", violation.getPropertyPath().toString(), violation.getMessage());
        }
        try {
            return converter.chart2response(charts.render(request, context));
        } catch (AgentChartException error) {
            return AiAgentChartRenderResponse.failure(error.code(), error.field(), error.getMessage());
        }
    }
}

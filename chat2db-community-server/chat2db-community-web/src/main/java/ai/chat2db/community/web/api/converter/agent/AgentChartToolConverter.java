package ai.chat2db.community.web.api.converter.agent;

import ai.chat2db.community.domain.api.model.agent.chart.AiAgentChart;
import ai.chat2db.community.domain.api.model.request.agent.AiAgentChartRenderRequest;
import ai.chat2db.community.domain.api.model.response.agent.AiAgentChartRenderResponse;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.type.LogicalType;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class AgentChartToolConverter {
    private final JsonMapper json = JsonMapper.builder().disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();

    public AgentChartToolConverter() {
        json.coercionConfigFor(LogicalType.Textual)
                .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail);
    }

    public AiAgentChartRenderRequest arguments2request(Map<String, Object> arguments) {
        return json.convertValue(arguments, AiAgentChartRenderRequest.class);
    }

    public AiAgentChartRenderResponse chart2response(AiAgentChart chart) {
        boolean partial = chart.page() != null && (chart.page().number() > 1 || !Boolean.FALSE.equals(chart.page().hasMore()));
        return new AiAgentChartRenderResponse(true, new AiAgentChartRenderResponse.RenderedChart(
                chart.id(), chart.resultId(), chart.chartType(), chart.title(), chart.data().size(), partial), null);
    }
}

package ai.chat2db.community.domain.api.model.agent.chart;

import ai.chat2db.community.domain.api.model.response.agent.DbAgentDatabaseResponse.Page;
import com.alibaba.fastjson2.JSONWriter;
import com.alibaba.fastjson2.annotation.JSONField;
import java.util.List;
import java.util.Map;

public record AiAgentChart(String id, String runId, String resultId, String chartType, String title,
        String xField, String yField, List<Series> series,
        @JSONField(serializeFeatures = JSONWriter.Feature.WriteMapNullValue) List<Map<String, Object>> data,
        Page page, List<String> warnings) {
    public record Series(String field, String chartType, String axisPosition) { }
}

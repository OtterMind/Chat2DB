package ai.chat2db.community.domain.api.model.agent.context;

import com.alibaba.fastjson2.JSONWriter;
import com.alibaba.fastjson2.annotation.JSONField;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record AiAgentRunContext(Environment environment,
        @JSONField(serializeFeatures = JSONWriter.Feature.WriteNulls) Scope selection,
        List<ObjectReference> objects) {
    public record Environment(String timeZone, String requestTime, String timeZoneSource) { }
    public record Scope(String dataSourceId, String dataSourceName, String databaseType, String database, String schema) { }
    public record ObjectReference(String dataSourceId, String dataSourceName, String databaseType,
            String database, String schema, String type, String name, String source) { }
}

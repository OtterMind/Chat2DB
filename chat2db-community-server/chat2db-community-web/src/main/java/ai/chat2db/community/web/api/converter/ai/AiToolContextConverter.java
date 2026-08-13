package ai.chat2db.community.web.api.converter.ai;

import java.util.Map;

import ai.chat2db.community.domain.api.model.runtime.ConnectionProfile;
import ai.chat2db.community.domain.api.model.agent.AgentDataScope;
import ai.chat2db.community.domain.api.model.request.ai.AiToolContextRequest;
import ai.chat2db.community.tools.model.Context;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.stereotype.Component;

@Component
public class AiToolContextConverter {

    public AiToolContextRequest toParam(ToolContext toolContext) {
        AiToolContextRequest param = new AiToolContextRequest();
        if (toolContext == null || toolContext.getContext() == null) {
            return param;
        }
        Map<String, Object> context = toolContext.getContext();
        Object dataSourceId = context.get("dataSourceId");
        if (dataSourceId instanceof Number number) {
            param.setDataSourceId(number.longValue());
        } else if (dataSourceId instanceof String stringValue && StringUtils.isNumeric(stringValue)) {
            param.setDataSourceId(Long.parseLong(stringValue));
        }
        param.setDatabaseName(context.get("databaseName") instanceof String value ? value : null);
        param.setSchemaName(context.get("schemaName") instanceof String value ? value : null);
        if (context.get("connectionProfile") instanceof ConnectionProfile profile) {
            param.setConnectionProfile(profile);
        }
        if (context.get("requestContext") instanceof Context requestContext) {
            param.setRequestContext(requestContext);
        }
        if (context.get("agentDataScope") instanceof AgentDataScope agentDataScope) {
            param.setAgentDataScope(agentDataScope);
        }
        if (context.get("agentRunId") instanceof String agentRunId) {
            param.setAgentRunId(agentRunId);
        }
        return param;
    }
}

package ai.chat2db.community.domain.api.model.request.ai;

import ai.chat2db.community.domain.api.model.agent.AgentDataScope;
import ai.chat2db.community.domain.api.model.runtime.ConnectionProfile;
import ai.chat2db.community.tools.model.Context;
import jakarta.validation.Valid;
import lombok.Data;

@Data
public class AiToolContextRequest {

    private Long dataSourceId;

    private String databaseName;

    private String schemaName;

    @Valid
    private ConnectionProfile connectionProfile;

    @Valid
    private Context requestContext;

    /**
     * Present only for Agent Runs. Generic Chat requests retain their existing
     * connection-context behavior.
     */
    @Valid
    private AgentDataScope agentDataScope;

    private String agentRunId;
}

package ai.chat2db.community.domain.api.model.agent;

import ai.chat2db.community.domain.api.enums.agent.AgentConnectorExecutionModeEnum;
import lombok.Data;

/**
 * Runtime attribution for a Connector audit Task. The bound Agent remains the
 * authorization principal; reasoning is performed by the external client.
 */
@Data
public class AgentConnectorAuditContext {

    private AgentConnectorExecutionModeEnum executionMode;

    private String externalRuntimeName;

    private String authorizationAgentId;

    private String authorizationAgentName;
}

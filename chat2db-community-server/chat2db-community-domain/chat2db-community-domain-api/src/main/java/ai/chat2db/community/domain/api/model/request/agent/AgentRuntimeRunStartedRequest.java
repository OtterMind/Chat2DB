package ai.chat2db.community.domain.api.model.request.agent;

import lombok.Data;

@Data
public class AgentRuntimeRunStartedRequest extends AgentRuntimeLeaseRenewRequest {

    private String runtimeExecutionId;
}

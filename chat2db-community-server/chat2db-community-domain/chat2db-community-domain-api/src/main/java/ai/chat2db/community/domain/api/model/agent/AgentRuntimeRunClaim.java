package ai.chat2db.community.domain.api.model.agent;

import ai.chat2db.community.domain.api.model.agent.runtime.AgentRuntimeStartRequest;
import lombok.Data;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

@Data
public class AgentRuntimeRunClaim {

    private String runId;
    private String taskId;
    private Integer leaseAttempt;
    private Long leaseRevision;
    private String leaseToken;
    private Date leaseExpiresAt;
    private String taskScopedToken;
    private String resumeSessionId;
    private List<AgentRuntimeMcpEndpoint> mcpEndpoints = new ArrayList<>();
    private AgentRuntimeProfile runtimeProfile;
    private AgentRuntimeStartRequest startRequest;
}

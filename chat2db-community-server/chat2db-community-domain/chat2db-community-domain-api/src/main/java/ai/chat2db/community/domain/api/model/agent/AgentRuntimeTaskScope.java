package ai.chat2db.community.domain.api.model.agent;

import lombok.Data;

import java.util.Date;
import java.util.List;

/**
 * Server-derived identity bound to a short-lived external Runtime task token.
 * Callers must not populate this model from request parameters.
 */
@Data
public class AgentRuntimeTaskScope {

    private String runId;
    private String taskId;
    private String agentId;
    private Long taskOwnerId;
    private String runtimeInstanceId;
    private Integer leaseAttempt;
    private Date expiresAt;
    private List<AgentDataScope> dataScopes;
}

package ai.chat2db.community.domain.api.model.request.agent;

import lombok.Data;

@Data
public class AgentRuntimeLeaseRenewRequest {

    private String daemonId;
    private Integer leaseAttempt;
    private Long expectedLeaseRevision;
}

package ai.chat2db.community.domain.api.model.agent;

import lombok.Data;

@Data
public class AgentRuntimeArtifactResult {

    private AgentArtifact artifact;
    private AgentRuntimeLeaseStatus lease;
}

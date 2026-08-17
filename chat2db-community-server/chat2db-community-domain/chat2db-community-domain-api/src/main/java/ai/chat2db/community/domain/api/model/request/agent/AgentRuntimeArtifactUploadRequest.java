package ai.chat2db.community.domain.api.model.request.agent;

import ai.chat2db.community.domain.api.model.agent.AgentRuntimeArtifactManifest;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class AgentRuntimeArtifactUploadRequest extends AgentRuntimeLeaseRenewRequest {

    private AgentRuntimeArtifactManifest manifest;
}

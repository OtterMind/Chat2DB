package ai.chat2db.community.domain.api.model.request.agent;

import ai.chat2db.community.domain.api.enums.agent.AgentArtifactContentModeEnum;
import lombok.Data;

@Data
public class AgentArtifactPublishRequest {
    private String artifactId;
    private Integer artifactVersion;
    private Integer chartIndex;
    private Long dashboardId;
    private AgentArtifactContentModeEnum contentMode;
    private Long publishedBy;
}

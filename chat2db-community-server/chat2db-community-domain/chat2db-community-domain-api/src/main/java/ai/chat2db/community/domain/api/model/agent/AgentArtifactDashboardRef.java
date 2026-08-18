package ai.chat2db.community.domain.api.model.agent;

import ai.chat2db.community.domain.api.enums.agent.AgentArtifactContentModeEnum;
import lombok.Data;

import java.util.Date;

@Data
public class AgentArtifactDashboardRef {
    private String id;
    private String taskId;
    private String artifactId;
    private Integer artifactVersion;
    private Integer chartIndex;
    private Long dashboardId;
    private Long chartId;
    private AgentArtifactContentModeEnum contentMode;
    private Long publishedBy;
    private Date publishedAt;
}

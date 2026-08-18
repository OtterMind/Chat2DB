package ai.chat2db.community.domain.api.model.agent;

import ai.chat2db.community.domain.api.enums.agent.AgentArtifactStatusEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentArtifactTypeEnum;
import lombok.Data;

import java.util.Date;

@Data
public class AgentArtifact {

    private String id;
    private String taskId;
    private AgentArtifactTypeEnum type;
    private String title;
    private AgentArtifactStatusEnum status;
    private Integer currentVersion;
    private String createdByRunId;
    private Long createdBy;
    private Date gmtCreate;
    private Date gmtModified;
    private Long revision;
}

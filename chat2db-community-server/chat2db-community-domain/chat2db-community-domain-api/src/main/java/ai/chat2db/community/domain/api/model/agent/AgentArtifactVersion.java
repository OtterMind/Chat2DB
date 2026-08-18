package ai.chat2db.community.domain.api.model.agent;

import ai.chat2db.community.domain.api.enums.agent.AgentArtifactContentModeEnum;
import lombok.Data;

import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;

@Data
public class AgentArtifactVersion {

    private String artifactId;
    private Integer version;
    private AgentArtifactContentModeEnum contentMode;
    private Map<String, Object> content = new LinkedHashMap<>();
    private String contentHash;
    private String createdByRunId;
    private Date createdAt;
    private Integer supersedesVersion;
}

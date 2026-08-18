package ai.chat2db.community.domain.api.model.request.agent;

import ai.chat2db.community.domain.api.enums.agent.AgentArtifactContentModeEnum;
import ai.chat2db.community.domain.api.model.agent.AgentArtifactEvidence;
import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
public class AgentArtifactVersionCreateRequest {

    private String artifactId;
    private Long expectedRevision;
    private AgentArtifactContentModeEnum contentMode = AgentArtifactContentModeEnum.SNAPSHOT;
    private Map<String, Object> content = new LinkedHashMap<>();
    private String createdByRunId;
    private List<AgentArtifactEvidence> evidence = new ArrayList<>();
}

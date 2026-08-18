package ai.chat2db.community.domain.api.model.agent;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class AgentArtifactDetail {

    private AgentArtifact artifact;
    private List<AgentArtifactVersion> versions = new ArrayList<>();
    private List<AgentArtifactEvidence> evidence = new ArrayList<>();
}

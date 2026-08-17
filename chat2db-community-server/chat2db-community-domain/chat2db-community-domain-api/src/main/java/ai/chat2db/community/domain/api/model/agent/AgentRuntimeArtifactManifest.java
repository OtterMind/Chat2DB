package ai.chat2db.community.domain.api.model.agent;

import ai.chat2db.community.domain.api.enums.agent.AgentArtifactTypeEnum;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class AgentRuntimeArtifactManifest {

    private String artifactId;
    private AgentArtifactTypeEnum type;
    private String title;
    private String mimeType;
    private String fileName;
    private Long size;
    private String sha256;
    private String content;
    private String contentBase64;
    private List<AgentRuntimeArtifactEvidenceRef> evidence = new ArrayList<>();
}

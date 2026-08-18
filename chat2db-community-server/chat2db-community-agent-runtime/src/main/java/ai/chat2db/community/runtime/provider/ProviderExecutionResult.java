package ai.chat2db.community.runtime.provider;

import lombok.Data;

import ai.chat2db.community.domain.api.model.agent.AgentRuntimeArtifactManifest;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.ArrayList;
import java.util.List;

@Data
public class ProviderExecutionResult {

    private String sessionId;
    private String turnId;
    private String finalResponse;
    private List<AgentRuntimeArtifactManifest> artifacts = new ArrayList<>();
    private Map<String, Object> usage = new LinkedHashMap<>();
}

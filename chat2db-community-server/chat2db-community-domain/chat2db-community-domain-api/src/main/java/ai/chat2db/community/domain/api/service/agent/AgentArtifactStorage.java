package ai.chat2db.community.domain.api.service.agent;

import ai.chat2db.community.domain.api.model.agent.AgentArtifact;

import java.util.List;

public interface AgentArtifactStorage {

    AgentArtifact create(AgentArtifact artifact, Long userId);

    AgentArtifact get(String sessionId, String artifactId, Long userId);

    List<AgentArtifact> list(String sessionId, Long userId);
}

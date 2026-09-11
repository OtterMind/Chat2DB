package ai.chat2db.community.tools.model.agent.runtime;

import java.util.List;

public record AgentRuntimeInput(String text, List<String> artifactIds, String skillName) {

    public AgentRuntimeInput(String text, List<String> artifactIds) {
        this(text, artifactIds, null);
    }

    public AgentRuntimeInput {
        if (skillName != null && !skillName.matches("[a-z0-9]+(?:-[a-z0-9]+)*")) {
            throw new IllegalArgumentException("Invalid skill name");
        }
        artifactIds = artifactIds == null ? List.of() : List.copyOf(artifactIds);
        if ((text == null || text.isBlank()) && artifactIds.isEmpty()) {
            throw new IllegalArgumentException("Runtime input must contain text or an artifact");
        }
        if (artifactIds.stream().anyMatch(id -> id == null || id.isBlank())) {
            throw new IllegalArgumentException("artifactIds must not contain blank values");
        }
    }
}

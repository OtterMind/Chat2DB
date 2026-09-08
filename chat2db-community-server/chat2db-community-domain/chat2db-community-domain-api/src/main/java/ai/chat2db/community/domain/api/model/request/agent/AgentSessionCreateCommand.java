package ai.chat2db.community.domain.api.model.request.agent;

import ai.chat2db.community.domain.api.model.agent.AgentDefinition;
import ai.chat2db.community.domain.api.model.agent.runtime.AgentRuntimeEnvironmentRequest;

import java.util.Objects;

public record AgentSessionCreateCommand(
        Long userId,
        String title,
        AgentDefinition definition,
        AgentRuntimeEnvironmentRequest environment) {

    public AgentSessionCreateCommand {
        Objects.requireNonNull(userId, "userId");
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("title must not be blank");
        }
        Objects.requireNonNull(definition, "definition");
        Objects.requireNonNull(environment, "environment");
    }
}

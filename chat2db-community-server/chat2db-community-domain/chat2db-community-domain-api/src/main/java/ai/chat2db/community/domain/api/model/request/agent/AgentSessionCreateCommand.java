package ai.chat2db.community.domain.api.model.request.agent;

import ai.chat2db.community.domain.api.model.agent.AgentRuntimeType;
import ai.chat2db.community.domain.api.model.agent.runtime.AgentRuntimeEnvironmentRequest;

import java.util.Objects;

public record AgentSessionCreateCommand(
        Long userId,
        String message,
        AgentRuntimeType runtimeType,
        String modelConfigId,
        AgentRuntimeEnvironmentRequest environment) {

    public AgentSessionCreateCommand {
        Objects.requireNonNull(userId, "userId");
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("message must not be blank");
        }
        Objects.requireNonNull(runtimeType, "runtimeType");
        if (modelConfigId == null || modelConfigId.isBlank()) {
            throw new IllegalArgumentException("modelConfigId must not be blank");
        }
        Objects.requireNonNull(environment, "environment");
    }
}

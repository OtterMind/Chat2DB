package ai.chat2db.community.web.api.model.request.agent;

import ai.chat2db.community.domain.api.model.agent.AgentRuntimeType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record AgentSessionCreateRequest(
        @NotBlank String message,
        @NotNull AgentRuntimeType runtimeType,
        @NotBlank String modelConfigId) {
}

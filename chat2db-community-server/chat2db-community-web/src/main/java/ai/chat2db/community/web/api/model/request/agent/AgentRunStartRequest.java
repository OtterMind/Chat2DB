package ai.chat2db.community.web.api.model.request.agent;

import jakarta.validation.constraints.NotBlank;

public record AgentRunStartRequest(
        @NotBlank String modelConfigId,
        @NotBlank String message,
        @NotBlank String idempotencyKey) {
}

package ai.chat2db.community.web.api.model.request.agent;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

public record AgentRunStartRequest(
        @NotBlank String modelConfigId,
        @NotBlank String message,
        @NotBlank String idempotencyKey,
        @Valid AgentRunContextRequest context) {
    public AgentRunStartRequest(String modelConfigId, String message, String idempotencyKey) {
        this(modelConfigId, message, idempotencyKey, null);
    }
}

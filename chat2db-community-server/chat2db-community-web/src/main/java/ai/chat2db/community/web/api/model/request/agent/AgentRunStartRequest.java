package ai.chat2db.community.web.api.model.request.agent;

import ai.chat2db.community.domain.api.model.agent.AgentModelSnapshot;
import ai.chat2db.community.domain.api.model.agent.runtime.AgentRuntimeInput;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record AgentRunStartRequest(
        @NotNull @Valid AgentModelSnapshot model,
        @NotNull @Valid AgentRuntimeInput input,
        @NotBlank String idempotencyKey) {
}

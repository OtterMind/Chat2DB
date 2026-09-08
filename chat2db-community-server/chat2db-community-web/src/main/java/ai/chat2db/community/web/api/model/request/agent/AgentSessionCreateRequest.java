package ai.chat2db.community.web.api.model.request.agent;

import ai.chat2db.community.domain.api.model.agent.AgentDefinition;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record AgentSessionCreateRequest(
        @NotNull Integer sessionVersion,
        @NotBlank @Size(max = 100) String title,
        @NotNull @Valid AgentDefinition definition) {
}

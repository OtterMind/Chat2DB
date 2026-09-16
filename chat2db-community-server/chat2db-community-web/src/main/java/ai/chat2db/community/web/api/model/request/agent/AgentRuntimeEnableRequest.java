package ai.chat2db.community.web.api.model.request.agent;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;

public record AgentRuntimeEnableRequest(
        @NotNull @AssertTrue(message = "Runtime Beta warning must be confirmed") Boolean confirmed) {
}

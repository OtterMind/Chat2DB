package ai.chat2db.community.web.api.model.request.agent;

import jakarta.validation.constraints.AssertTrue;

public record AgentRuntimeEnableRequest(
        @AssertTrue(message = "Runtime Beta warning must be confirmed") Boolean confirmed) {
}

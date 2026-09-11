package ai.chat2db.community.web.api.model.request.agent;

import jakarta.validation.constraints.NotBlank;

public record AgentRunCancelRequest(@NotBlank String sessionId) {
}

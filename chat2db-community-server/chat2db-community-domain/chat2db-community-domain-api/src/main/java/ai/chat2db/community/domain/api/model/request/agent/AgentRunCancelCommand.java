package ai.chat2db.community.domain.api.model.request.agent;

import java.util.Objects;

public record AgentRunCancelCommand(Long userId, String sessionId, String runId) {

    public AgentRunCancelCommand {
        Objects.requireNonNull(userId, "userId");
        requireText(sessionId, "sessionId");
        requireText(runId, "runId");
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}

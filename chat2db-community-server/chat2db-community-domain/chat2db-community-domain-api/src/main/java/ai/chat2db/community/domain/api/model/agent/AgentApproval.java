package ai.chat2db.community.domain.api.model.agent;

import ai.chat2db.community.domain.api.enums.agent.AgentApprovalScope;
import ai.chat2db.community.domain.api.enums.agent.AgentApprovalStatus;
import java.time.LocalDateTime;
import java.util.Objects;

public record AgentApproval(
        String id,
        String sessionId,
        String runId,
        String toolCallId,
        AgentApprovalStatus status,
        AgentApprovalScope scope,
        String subjectSha256,
        LocalDateTime expiresAt) {

    public AgentApproval {
        requireText(id, "id");
        requireText(sessionId, "sessionId");
        requireText(runId, "runId");
        requireText(toolCallId, "toolCallId");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(scope, "scope");
        if (subjectSha256 == null || !subjectSha256.matches("[a-f0-9]{64}")) {
            throw new IllegalArgumentException("subjectSha256 must be a lowercase SHA-256 value");
        }
        Objects.requireNonNull(expiresAt, "expiresAt");
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}

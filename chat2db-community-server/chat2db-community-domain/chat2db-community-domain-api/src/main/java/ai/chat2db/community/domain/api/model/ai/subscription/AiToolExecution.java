package ai.chat2db.community.domain.api.model.ai.subscription;

import java.time.Instant;

public record AiToolExecution(
        String attemptId,
        long sequence,
        String toolName,
        String argumentsHash,
        String effectFingerprint,
        AiToolExecutionState state,
        String safeResultReference,
        Instant updatedAt) {
}

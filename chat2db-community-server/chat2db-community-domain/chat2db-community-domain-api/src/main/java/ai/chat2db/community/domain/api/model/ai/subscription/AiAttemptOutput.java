package ai.chat2db.community.domain.api.model.ai.subscription;

import java.time.Instant;

public record AiAttemptOutput(
        String attemptId,
        long sequence,
        AiAttemptOutputKind kind,
        String content,
        boolean visible,
        boolean contextEligible,
        Instant createdAt) {
}

package ai.chat2db.community.domain.api.model.ai.subscription;

import ai.chat2db.community.domain.api.enums.ai.AiProviderEnum;

import java.time.Instant;

public record AiAttempt(
        String attemptId,
        String messageId,
        AiProviderEnum provider,
        AiAttemptState state,
        String externalThreadId,
        String externalTurnId,
        Instant createdAt,
        Instant updatedAt) {
}

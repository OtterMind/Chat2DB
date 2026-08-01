package ai.chat2db.community.domain.api.model.ai.subscription;

import ai.chat2db.community.domain.api.enums.ai.AiProviderEnum;

import java.time.Instant;

public record AiProviderLease(
        AiProviderEnum provider,
        String attemptId,
        long fenceGeneration,
        Instant acquiredAt) {
}

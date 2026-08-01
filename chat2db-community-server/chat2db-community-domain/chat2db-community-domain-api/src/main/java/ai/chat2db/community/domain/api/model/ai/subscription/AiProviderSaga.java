package ai.chat2db.community.domain.api.model.ai.subscription;

import ai.chat2db.community.domain.api.enums.ai.AiProviderEnum;

import java.time.Instant;

public record AiProviderSaga(
        String sagaId,
        AiProviderEnum provider,
        AiProviderSagaState state,
        long fenceGeneration,
        Instant updatedAt) {
}

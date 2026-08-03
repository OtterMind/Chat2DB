package ai.chat2db.community.domain.api.model.ai.subscription;

import ai.chat2db.community.domain.api.enums.ai.AiProviderEnum;

import java.time.Instant;

public record AiProviderConnection(
        AiProviderEnum provider,
        AiProviderConnectionState state,
        String maskedAccount,
        long fenceGeneration,
        Instant discoveredAt,
        String discoveryErrorCode) {
}

package ai.chat2db.community.domain.api.model.ai.subscription;

import ai.chat2db.community.domain.api.enums.ai.AiProviderEnum;

import java.util.Objects;

public record AiModelRef(
        AiAccessType accessType,
        AiProviderEnum provider,
        AiRouteKind routeKind,
        String modelId) {

    public AiModelRef {
        Objects.requireNonNull(accessType, "accessType");
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(routeKind, "routeKind");
        if (modelId == null || modelId.isBlank()) {
            throw new IllegalArgumentException("modelId must not be blank");
        }
        boolean apiKeyRoute = accessType == AiAccessType.API_KEY
                && routeKind == AiRouteKind.SPRING_AI_API_KEY;
        boolean chatGptSubscriptionRoute = accessType == AiAccessType.SUBSCRIPTION
                && provider == AiProviderEnum.OPENAI
                && routeKind == AiRouteKind.CHATGPT_CODEX_APP_SERVER;
        if (!apiKeyRoute && !chatGptSubscriptionRoute) {
            throw new IllegalArgumentException("Unsupported AI access type, provider, and route combination");
        }
    }
}

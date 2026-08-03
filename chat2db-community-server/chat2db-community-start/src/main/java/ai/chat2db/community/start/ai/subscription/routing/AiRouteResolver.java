package ai.chat2db.community.start.ai.subscription.routing;

import ai.chat2db.community.domain.api.enums.ai.AiProviderEnum;
import ai.chat2db.community.domain.api.model.ai.subscription.AiAccessType;
import ai.chat2db.community.domain.api.model.ai.subscription.AiModelRef;
import ai.chat2db.community.domain.api.model.ai.subscription.AiModelRefKey;
import ai.chat2db.community.domain.api.model.ai.subscription.AiRouteKind;
import ai.chat2db.community.web.api.model.request.ai.ChatRequest;

import java.util.Objects;

/**
 * Resolves the provider-neutral route for a chat request without performing provider fallback.
 */
public final class AiRouteResolver {

    public AiRouteDecision resolve(ChatRequest request) {
        Objects.requireNonNull(request, "request");
        AiAccessType accessType = request.getAccessType() == null
                ? AiAccessType.API_KEY
                : request.getAccessType();

        if (accessType == AiAccessType.API_KEY) {
            AiProviderEnum provider = request.getProvider() == null
                    ? AiProviderEnum.OPENAI
                    : request.getProvider();
            String modelId = blankToDefault(request.getModel(), "default");
            try {
                return AiRouteDecision.apiKey(new AiModelRef(
                        AiAccessType.API_KEY,
                        provider,
                        AiRouteKind.SPRING_AI_API_KEY,
                        modelId));
            } catch (IllegalArgumentException ex) {
                return AiRouteDecision.rejected("UNSUPPORTED_API_KEY_MODEL_REF");
            }
        }

        if (accessType == AiAccessType.SUBSCRIPTION) {
            if (request.getModelRefKey() != null && !request.getModelRefKey().isBlank()) {
                AiModelRef decoded = AiModelRefKey.decode(request.getModelRefKey()).orElse(null);
                if (decoded == null || decoded.accessType() != AiAccessType.SUBSCRIPTION) {
                    return AiRouteDecision.rejected("INVALID_SUBSCRIPTION_MODEL_REF_KEY");
                }
                if (request.getProvider() != null && request.getProvider() != decoded.provider()) {
                    return AiRouteDecision.rejected("SUBSCRIPTION_MODEL_REF_MISMATCH");
                }
                if (request.getModel() != null && !request.getModel().isBlank()
                        && !request.getModel().equals(decoded.modelId())) {
                    return AiRouteDecision.rejected("SUBSCRIPTION_MODEL_REF_MISMATCH");
                }
                return AiRouteDecision.subscription(decoded);
            }
            AiProviderEnum provider = request.getProvider();
            if (provider == null) {
                provider = AiProviderEnum.OPENAI;
            }
            String modelId = request.getModel();
            if (modelId == null || modelId.isBlank()) {
                return AiRouteDecision.rejected("SUBSCRIPTION_MODEL_REQUIRED");
            }
            try {
                return AiRouteDecision.subscription(new AiModelRef(
                        AiAccessType.SUBSCRIPTION,
                        provider,
                        AiRouteKind.CHATGPT_CODEX_APP_SERVER,
                        modelId));
            } catch (IllegalArgumentException ex) {
                return AiRouteDecision.rejected("UNSUPPORTED_SUBSCRIPTION_MODEL_REF");
            }
        }

        return AiRouteDecision.rejected("UNKNOWN_ACCESS_TYPE");
    }

    private static String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}

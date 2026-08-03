package ai.chat2db.community.start.ai.subscription.routing;

import ai.chat2db.community.domain.api.model.ai.subscription.AiModelRef;
import ai.chat2db.community.domain.api.model.ai.subscription.AiRouteKind;

/**
 * Provider-neutral route choice for one chat send.
 */
public final class AiRouteDecision {

    private final AiRouteKind routeKind;
    private final AiModelRef modelRef;
    private final String rejectCode;

    private AiRouteDecision(AiRouteKind routeKind, AiModelRef modelRef, String rejectCode) {
        this.routeKind = routeKind;
        this.modelRef = modelRef;
        this.rejectCode = rejectCode;
    }

    public static AiRouteDecision apiKey(AiModelRef modelRef) {
        return new AiRouteDecision(AiRouteKind.SPRING_AI_API_KEY, modelRef, null);
    }

    public static AiRouteDecision subscription(AiModelRef modelRef) {
        return new AiRouteDecision(AiRouteKind.CHATGPT_CODEX_APP_SERVER, modelRef, null);
    }

    public static AiRouteDecision rejected(String rejectCode) {
        return new AiRouteDecision(null, null, rejectCode);
    }

    public boolean isRejected() {
        return rejectCode != null;
    }

    public AiRouteKind routeKind() {
        return routeKind;
    }

    public AiModelRef modelRef() {
        return modelRef;
    }

    public String rejectCode() {
        return rejectCode;
    }

    public boolean isSubscription() {
        return routeKind == AiRouteKind.CHATGPT_CODEX_APP_SERVER;
    }

    public boolean isApiKey() {
        return routeKind == AiRouteKind.SPRING_AI_API_KEY;
    }
}

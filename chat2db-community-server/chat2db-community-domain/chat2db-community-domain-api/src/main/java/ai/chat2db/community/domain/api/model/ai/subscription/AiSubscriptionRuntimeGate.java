package ai.chat2db.community.domain.api.model.ai.subscription;

public final class AiSubscriptionRuntimeGate {

    private AiSubscriptionRuntimeGate() {
    }

    public static AiSubscriptionCapability evaluate(
            boolean featureEnabled,
            boolean communityRuntime,
            boolean desktop,
            boolean guiEnabled,
            boolean packagedRelease,
            boolean keyringAvailable,
            boolean appServerAvailable) {
        if (!featureEnabled) {
            return AiSubscriptionCapability.disabled(AiSubscriptionDisabledReason.FEATURE_DISABLED);
        }
        if (!communityRuntime) {
            return AiSubscriptionCapability.disabled(AiSubscriptionDisabledReason.NOT_COMMUNITY_RUNTIME);
        }
        if (!desktop) {
            return AiSubscriptionCapability.disabled(AiSubscriptionDisabledReason.NOT_DESKTOP);
        }
        if (!guiEnabled) {
            return AiSubscriptionCapability.disabled(AiSubscriptionDisabledReason.GUI_DISABLED);
        }
        if (!packagedRelease) {
            return AiSubscriptionCapability.disabled(AiSubscriptionDisabledReason.NOT_PACKAGED_RELEASE);
        }
        if (!keyringAvailable) {
            return AiSubscriptionCapability.disabled(AiSubscriptionDisabledReason.KEYRING_UNAVAILABLE);
        }
        if (!appServerAvailable) {
            return AiSubscriptionCapability.disabled(AiSubscriptionDisabledReason.APP_SERVER_UNAVAILABLE);
        }
        return AiSubscriptionCapability.enabledCapability();
    }
}

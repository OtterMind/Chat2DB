package ai.chat2db.community.domain.api.model.ai.subscription;

public record AiSubscriptionCapability(boolean enabled, AiSubscriptionDisabledReason disabledReason) {

    public static AiSubscriptionCapability enabledCapability() {
        return new AiSubscriptionCapability(true, AiSubscriptionDisabledReason.NONE);
    }

    public static AiSubscriptionCapability disabled(AiSubscriptionDisabledReason reason) {
        return new AiSubscriptionCapability(false, reason);
    }
}

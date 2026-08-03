package ai.chat2db.community.domain.api.model.ai.subscription;

public enum AiSubscriptionDisabledReason {
    NONE,
    FEATURE_DISABLED,
    NOT_COMMUNITY_RUNTIME,
    NOT_DESKTOP,
    GUI_DISABLED,
    NOT_PACKAGED_RELEASE,
    KEYRING_UNAVAILABLE,
    APP_SERVER_UNAVAILABLE
}

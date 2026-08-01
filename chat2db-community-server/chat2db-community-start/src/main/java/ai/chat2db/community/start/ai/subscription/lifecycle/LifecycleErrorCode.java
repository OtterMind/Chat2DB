package ai.chat2db.community.start.ai.subscription.lifecycle;

/**
 * Safe error codes for the subscription lifecycle. Never attach tokens, auth URLs, or secret detail.
 */
public enum LifecycleErrorCode {
    FEATURE_DISABLED,
    APP_SERVER_UNAVAILABLE,
    PROVIDER_BUSY,
    INVALID_ATTEMPT,
    ATTEMPT_EXPIRED,
    ATTEMPT_CANCELLED,
    ATTEMPT_NOT_FOUND,
    BROWSER_TARGET_NOT_ALLOWED,
    LOGIN_NOT_AUTHENTICATED,
    DISCOVERY_FAILED,
    SIGN_OUT_FAILED,
    ILLEGAL_STATE
}

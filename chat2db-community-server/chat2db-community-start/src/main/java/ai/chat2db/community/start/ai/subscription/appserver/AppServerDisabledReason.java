package ai.chat2db.community.start.ai.subscription.appserver;

/**
 * Explicit reasons the ChatGPT app-server route stays disabled.
 * Missing gates never fall back to a private protocol or file credential store.
 */
public enum AppServerDisabledReason {
    FEATURE_DISABLED_BY_DEFAULT,
    RUNTIME_GATES_NOT_SATISFIED,
    BINARY_MISSING,
    BINARY_VERSION_MISMATCH,
    BINARY_CHECKSUM_MISMATCH,
    KEYRING_UNAVAILABLE,
    PROTOCOL_MISMATCH,
    CAPABILITY_PROBE_FAILED,
    PROCESS_CRASHED,
    PROCESS_NOT_RUNNING,
    SHUTDOWN,
    OVERSIZED_MESSAGE,
    MALFORMED_MESSAGE,
    METHOD_NOT_ALLOWLISTED
}

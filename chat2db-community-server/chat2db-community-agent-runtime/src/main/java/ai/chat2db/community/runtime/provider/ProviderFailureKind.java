package ai.chat2db.community.runtime.provider;

public enum ProviderFailureKind {
    PROTOCOL_ERROR,
    PROCESS_EXIT,
    INACTIVITY_TIMEOUT,
    CANCELLED
}

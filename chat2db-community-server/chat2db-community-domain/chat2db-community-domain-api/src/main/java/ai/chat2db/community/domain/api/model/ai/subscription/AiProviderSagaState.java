package ai.chat2db.community.domain.api.model.ai.subscription;

import java.util.EnumSet;
import java.util.Set;

public enum AiProviderSagaState {
    DISCONNECT_REQUESTED,
    WORK_FENCED,
    LOGOUT_REQUESTED,
    CREDENTIAL_REMOVED,
    LOCAL_CLEANUP,
    DISCONNECTED,
    DISCONNECT_FAILED;

    public boolean isRecoverable() {
        return this != DISCONNECTED;
    }

    public boolean canTransitionTo(AiProviderSagaState target) {
        return allowedTargets().contains(target);
    }

    private Set<AiProviderSagaState> allowedTargets() {
        return switch (this) {
            case DISCONNECT_REQUESTED -> EnumSet.of(WORK_FENCED);
            case WORK_FENCED -> EnumSet.of(LOGOUT_REQUESTED);
            case LOGOUT_REQUESTED -> EnumSet.of(CREDENTIAL_REMOVED, DISCONNECT_FAILED);
            case DISCONNECT_FAILED -> EnumSet.of(LOGOUT_REQUESTED);
            case CREDENTIAL_REMOVED -> EnumSet.of(LOCAL_CLEANUP);
            case LOCAL_CLEANUP -> EnumSet.of(DISCONNECTED);
            case DISCONNECTED -> EnumSet.noneOf(AiProviderSagaState.class);
        };
    }
}

package ai.chat2db.community.domain.api.model.ai.subscription;

import java.util.EnumSet;
import java.util.Set;

public enum AiProviderConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    DISCOVERY_FAILED,
    DISCONNECTING,
    DISCONNECT_FAILED,
    DISABLED;

    public boolean canTransitionTo(AiProviderConnectionState target) {
        return allowedTargets().contains(target);
    }

    private Set<AiProviderConnectionState> allowedTargets() {
        return switch (this) {
            case DISCONNECTED -> EnumSet.of(CONNECTING, DISABLED);
            case CONNECTING -> EnumSet.of(CONNECTED, DISCONNECTED, DISABLED);
            case CONNECTED -> EnumSet.of(DISCOVERY_FAILED, DISCONNECTING, DISABLED);
            case DISCOVERY_FAILED -> EnumSet.of(CONNECTED, DISCONNECTING, DISABLED);
            case DISCONNECTING -> EnumSet.of(DISCONNECTED, DISCONNECT_FAILED);
            case DISCONNECT_FAILED -> EnumSet.of(DISCONNECTING, DISABLED);
            case DISABLED -> EnumSet.of(DISCONNECTED);
        };
    }
}

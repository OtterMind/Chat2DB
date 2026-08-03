package ai.chat2db.community.domain.api.model.ai.subscription;

import java.util.EnumSet;
import java.util.Set;

public enum AiAttemptState {
    CREATED,
    SUBMITTING,
    ACTIVE,
    TOOL_ACTIVE,
    OUTPUT_VISIBLE,
    COMPLETED,
    FAILED,
    INTERRUPTED,
    OUTCOME_UNKNOWN,
    TOOL_OUTCOME_UNKNOWN;

    public boolean canTransitionTo(AiAttemptState target) {
        return allowedTargets().contains(target);
    }

    private Set<AiAttemptState> allowedTargets() {
        return switch (this) {
            case CREATED -> EnumSet.of(SUBMITTING, INTERRUPTED);
            case SUBMITTING -> EnumSet.of(ACTIVE, FAILED, INTERRUPTED, OUTCOME_UNKNOWN);
            // ACTIVE/OUTPUT_VISIBLE may become OUTCOME_UNKNOWN when the provider stops emitting events
            // after turn/start (absolute timeout or idle watchdog) without a proven completion.
            case ACTIVE -> EnumSet.of(TOOL_ACTIVE, OUTPUT_VISIBLE, COMPLETED, FAILED, INTERRUPTED, OUTCOME_UNKNOWN);
            case TOOL_ACTIVE -> EnumSet.of(ACTIVE, FAILED, INTERRUPTED, TOOL_OUTCOME_UNKNOWN);
            case OUTPUT_VISIBLE -> EnumSet.of(TOOL_ACTIVE, COMPLETED, FAILED, INTERRUPTED, OUTCOME_UNKNOWN);
            case COMPLETED, FAILED, INTERRUPTED, OUTCOME_UNKNOWN, TOOL_OUTCOME_UNKNOWN -> EnumSet.noneOf(AiAttemptState.class);
        };
    }
}

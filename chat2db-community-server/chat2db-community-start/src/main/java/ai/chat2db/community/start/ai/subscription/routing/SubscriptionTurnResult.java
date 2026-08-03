package ai.chat2db.community.start.ai.subscription.routing;

import ai.chat2db.community.domain.api.model.ai.subscription.AiAttemptOutput;
import ai.chat2db.community.domain.api.model.ai.subscription.AiAttemptState;

import java.util.List;

/**
 * Durable outcome of one subscription turn attempt. Never carries provider tokens.
 */
public record SubscriptionTurnResult(
        String attemptId,
        String messageId,
        AiAttemptState state,
        String externalThreadId,
        String externalTurnId,
        List<AiAttemptOutput> outputs,
        String errorCode,
        boolean providerBusy) {

    public static SubscriptionTurnResult busy(String messageId) {
        return new SubscriptionTurnResult(
                null,
                messageId,
                AiAttemptState.FAILED,
                null,
                null,
                List.of(),
                "PROVIDER_BUSY",
                true);
    }

    public static SubscriptionTurnResult rejected(String messageId, String errorCode) {
        return new SubscriptionTurnResult(
                null,
                messageId,
                AiAttemptState.FAILED,
                null,
                null,
                List.of(),
                errorCode,
                false);
    }
}

package ai.chat2db.community.start.ai.subscription.runtime;

import ai.chat2db.community.domain.api.model.ai.subscription.AiAttemptState;
import ai.chat2db.community.start.ai.subscription.appserver.AppServerEventListener;

/** Attempt-scoped stream callback used to make durable work fences immediately visible in-process. */
public interface SubscriptionAttemptFenceListener extends AppServerEventListener {

    String attemptId();

    void onAttemptFenced(AiAttemptState state, String errorCode);
}

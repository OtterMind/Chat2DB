package ai.chat2db.community.start.ai.subscription.runtime;

import ai.chat2db.community.domain.api.enums.ai.AiProviderEnum;
import ai.chat2db.community.domain.api.model.ai.subscription.AiAttempt;
import ai.chat2db.community.domain.api.model.ai.subscription.AiModelRef;
import ai.chat2db.community.domain.api.model.ai.subscription.AiModelSnapshot;
import ai.chat2db.community.domain.api.model.ai.subscription.AiProviderConnection;
import ai.chat2db.community.domain.api.model.ai.subscription.AiSubscriptionCapability;
import ai.chat2db.community.start.ai.subscription.lifecycle.SafeLoginStartResponse;

import java.util.List;
import java.util.Optional;

/** Secret-free control-plane surface consumed by the HTTP/JCEF controller. */
public interface SubscriptionAiFacade {

    AiSubscriptionCapability capability();

    List<ProviderView> providers();

    SafeLoginStartResponse startConnect(AiProviderEnum provider);

    void cancelConnect(AiProviderEnum provider, String attemptId);

    AiProviderConnection disconnect(AiProviderEnum provider);

    AiProviderConnection retryDiscovery(AiProviderEnum provider);

    List<AiModelSnapshot> models();

    List<AiModelSnapshot> refreshModels(AiProviderEnum provider);

    Optional<AiModelRef> globalDefault();

    Optional<AiModelRef> conversationModel(String conversationId);

    void setGlobalDefault(AiModelRef modelRef);

    void setConversationModel(String conversationId, AiModelRef modelRef);

    List<AiAttempt> attempts(String messageId, String conversationId);

    record ProviderView(
            AiProviderEnum provider,
            String displayName,
            AiProviderConnection connection,
            boolean eligible,
            boolean showAccountManagement,
            String disabledReason,
            boolean reauthRequired) {
    }
}

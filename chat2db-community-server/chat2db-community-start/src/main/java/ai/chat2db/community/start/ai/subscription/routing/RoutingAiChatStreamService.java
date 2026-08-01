package ai.chat2db.community.start.ai.subscription.routing;

import ai.chat2db.community.domain.api.service.ai.IAiChatStreamService;
import ai.chat2db.community.web.api.model.request.ai.ChatRequest;

import java.util.Objects;
import java.util.function.Function;

/**
 * Provider-neutral dispatch: API-key requests are delegated unchanged to the existing stream service;
 * subscription requests use {@link SubscriptionTurnService}. Default remains API-key when accessType is null.
 * <p>
 * This class is not registered as a Spring {@code @Primary} bean so existing controllers keep using
 * {@code AiChatStreamAdapter} until a later ticket wires dispatch.
 */
public final class RoutingAiChatStreamService<R> implements IAiChatStreamService<ChatRequest, R> {

    private final IAiChatStreamService<ChatRequest, R> apiKeyDelegate;
    private final AiRouteResolver routeResolver;
    private final SubscriptionTurnService subscriptionTurnService;
    private final Function<SubscriptionTurnResult, R> subscriptionResultMapper;

    public RoutingAiChatStreamService(
            IAiChatStreamService<ChatRequest, R> apiKeyDelegate,
            AiRouteResolver routeResolver,
            SubscriptionTurnService subscriptionTurnService,
            Function<SubscriptionTurnResult, R> subscriptionResultMapper) {
        this.apiKeyDelegate = Objects.requireNonNull(apiKeyDelegate, "apiKeyDelegate");
        this.routeResolver = Objects.requireNonNull(routeResolver, "routeResolver");
        this.subscriptionTurnService = Objects.requireNonNull(subscriptionTurnService, "subscriptionTurnService");
        this.subscriptionResultMapper = Objects.requireNonNull(subscriptionResultMapper, "subscriptionResultMapper");
    }

    @Override
    public R stream(ChatRequest request) {
        AiRouteDecision decision = routeResolver.resolve(request);
        if (decision.isRejected()) {
            return subscriptionResultMapper.apply(
                    SubscriptionTurnResult.rejected(request.getMessageId(), decision.rejectCode()));
        }
        if (decision.isApiKey()) {
            return apiKeyDelegate.stream(request);
        }
        SubscriptionTurnResult result = subscriptionTurnService.execute(request, decision.modelRef());
        return subscriptionResultMapper.apply(result);
    }
}

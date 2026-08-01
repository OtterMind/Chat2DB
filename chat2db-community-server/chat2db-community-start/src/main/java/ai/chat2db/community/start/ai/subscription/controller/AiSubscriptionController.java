package ai.chat2db.community.start.ai.subscription.controller;

import ai.chat2db.community.domain.api.enums.ai.AiProviderEnum;
import ai.chat2db.community.domain.api.model.ai.subscription.AiAttempt;
import ai.chat2db.community.domain.api.model.ai.subscription.AiModelRef;
import ai.chat2db.community.domain.api.model.ai.subscription.AiModelRefKey;
import ai.chat2db.community.domain.api.model.ai.subscription.AiModelSnapshot;
import ai.chat2db.community.domain.api.model.ai.subscription.AiProviderConnection;
import ai.chat2db.community.domain.api.model.ai.subscription.AiSubscriptionCapability;
import ai.chat2db.community.start.ai.subscription.lifecycle.LifecycleException;
import ai.chat2db.community.start.ai.subscription.lifecycle.SafeLoginStartResponse;
import ai.chat2db.community.start.ai.subscription.runtime.SubscriptionAiFacade;
import ai.chat2db.community.start.ai.subscription.runtime.SubscriptionDesktopRuntimeCondition;
import ai.chat2db.community.tools.wrapper.result.ActionResult;
import ai.chat2db.community.tools.wrapper.result.DataResult;
import org.springframework.context.annotation.Conditional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Secret-free control plane for packaged Community JCEF subscription AI. */
@RestController
@RequestMapping("/api/v3/ai/subscription")
@Conditional(SubscriptionDesktopRuntimeCondition.class)
public class AiSubscriptionController {

    private final SubscriptionAiFacade facade;

    public AiSubscriptionController(SubscriptionAiFacade facade) {
        this.facade = facade;
    }

    @GetMapping("/capability")
    public DataResult<AiSubscriptionCapability> capability() {
        return DataResult.of(facade.capability());
    }

    @GetMapping("/providers")
    public DataResult<List<ProviderResponse>> providers() {
        return DataResult.of(facade.providers().stream().map(ProviderResponse::from).toList());
    }

    @PostMapping("/connect/start")
    public DataResult<StartConnectResponse> startConnect(@RequestBody ProviderRequest request) {
        try {
            SafeLoginStartResponse response = facade.startConnect(requireChatGpt(request.provider()));
            return DataResult.of(new StartConnectResponse(
                    response.attemptId(), request.provider(), "STARTED", null));
        } catch (RuntimeException exception) {
            return DataResult.of(new StartConnectResponse(null, request.provider(), "FAILED", safeCode(exception)));
        }
    }

    @PostMapping("/connect/cancel")
    public ActionResult cancelConnect(@RequestBody CancelConnectRequest request) {
        facade.cancelConnect(requireChatGpt(request.provider()), request.attemptId());
        return ActionResult.isSuccess();
    }

    @PostMapping({"/disconnect", "/disconnect/retry"})
    public DataResult<ProviderResponse> disconnect(@RequestBody ProviderRequest request) {
        AiProviderConnection connection = facade.disconnect(requireChatGpt(request.provider()));
        return DataResult.of(ProviderResponse.chatGpt(connection, facade.capability()));
    }

    @PostMapping("/discovery/retry")
    public DataResult<ProviderResponse> retryDiscovery(@RequestBody ProviderRequest request) {
        AiProviderConnection connection = facade.retryDiscovery(requireChatGpt(request.provider()));
        return DataResult.of(ProviderResponse.chatGpt(connection, facade.capability()));
    }

    @GetMapping("/models")
    public DataResult<List<ModelResponse>> models() {
        return DataResult.of(facade.models().stream().map(ModelResponse::from).toList());
    }

    @PostMapping("/models/refresh")
    public DataResult<List<ModelResponse>> refreshModels(@RequestBody(required = false) ProviderRequest request) {
        AiProviderEnum provider = request == null || request.provider() == null
                ? AiProviderEnum.OPENAI : requireChatGpt(request.provider());
        return DataResult.of(facade.refreshModels(provider).stream().map(ModelResponse::from).toList());
    }

    @GetMapping("/preferences")
    public DataResult<PreferenceResponse> preferences(
            @RequestParam(value = "conversationId", required = false) String conversationId) {
        return DataResult.of(new PreferenceResponse(
                facade.globalDefault().map(AiModelRefKey::encode).orElse(null),
                conversationId == null ? null
                        : facade.conversationModel(conversationId).map(AiModelRefKey::encode).orElse(null)));
    }

    @PostMapping("/preferences/global-default")
    public DataResult<PreferenceResponse> setGlobalDefault(@RequestBody ModelPreferenceRequest request) {
        AiModelRef ref = requireModelRef(request.modelRefKey());
        facade.setGlobalDefault(ref);
        return preferences(null);
    }

    @PostMapping("/preferences/conversation")
    public DataResult<PreferenceResponse> setConversationModel(@RequestBody ModelPreferenceRequest request) {
        if (request.conversationId() == null || request.conversationId().isBlank()) {
            throw new IllegalArgumentException("conversationId required");
        }
        facade.setConversationModel(request.conversationId(), requireModelRef(request.modelRefKey()));
        return preferences(request.conversationId());
    }

    @GetMapping("/attempts")
    public DataResult<List<AttemptResponse>> attempts(
            @RequestParam(value = "messageId", required = false) String messageId,
            @RequestParam(value = "conversationId", required = false) String conversationId) {
        return DataResult.of(facade.attempts(messageId, conversationId).stream().map(AttemptResponse::from).toList());
    }

    private static AiProviderEnum requireChatGpt(AiProviderEnum provider) {
        if (provider != AiProviderEnum.OPENAI) {
            throw new IllegalArgumentException("provider is not eligible for subscription login");
        }
        return provider;
    }

    private static AiModelRef requireModelRef(String key) {
        return AiModelRefKey.decode(key).orElseThrow(() -> new IllegalArgumentException("invalid modelRefKey"));
    }

    private static String safeCode(RuntimeException exception) {
        if (exception instanceof LifecycleException lifecycleException) {
            return lifecycleException.errorCode().name();
        }
        String name = exception.getClass().getSimpleName();
        return name == null || name.isBlank() ? "CONNECT_FAILED" : name.toUpperCase();
    }

    public record ProviderRequest(AiProviderEnum provider) {
    }

    public record CancelConnectRequest(AiProviderEnum provider, String attemptId) {
    }

    public record ModelPreferenceRequest(String conversationId, String modelRefKey) {
    }

    public record StartConnectResponse(String attemptId, AiProviderEnum provider, String status, String errorCode) {
    }

    public record PreferenceResponse(String globalDefaultModelRefKey, String conversationModelRefKey) {
    }

    public record ProviderResponse(
            AiProviderEnum provider,
            String displayName,
            String state,
            String maskedAccount,
            long fenceGeneration,
            String discoveredAt,
            String discoveryErrorCode,
            boolean reauthRequired,
            String disabledReason,
            boolean eligible,
            boolean showAccountManagement) {

        static ProviderResponse from(SubscriptionAiFacade.ProviderView view) {
            AiProviderConnection connection = view.connection();
            return new ProviderResponse(
                    view.provider(), view.displayName(), connection.state().name(), connection.maskedAccount(),
                    connection.fenceGeneration(),
                    connection.discoveredAt() == null ? null : connection.discoveredAt().toString(),
                    connection.discoveryErrorCode(), false, view.disabledReason(),
                    view.eligible(), view.showAccountManagement());
        }

        static ProviderResponse chatGpt(AiProviderConnection connection, AiSubscriptionCapability capability) {
            return from(new SubscriptionAiFacade.ProviderView(
                    AiProviderEnum.OPENAI, "ChatGPT", connection, capability.enabled(), true,
                    capability.disabledReason().name()));
        }
    }

    public record ModelResponse(
            AiModelRef modelRef,
            String modelRefKey,
            String displayName,
            String discoveredAt,
            boolean available,
            String disabledReason,
            List<String> supportedReasoningEfforts,
            String defaultReasoningEffort,
            String planType) {

        static ModelResponse from(AiModelSnapshot snapshot) {
            return new ModelResponse(snapshot.modelRef(), AiModelRefKey.encode(snapshot.modelRef()),
                    snapshot.displayName(), snapshot.discoveredAt().toString(), snapshot.available(),
                    snapshot.disabledReason(), snapshot.supportedReasoningEfforts(),
                    snapshot.defaultReasoningEffort(), null);
        }
    }

    public record AttemptResponse(
            String attemptId,
            String messageId,
            AiProviderEnum provider,
            String state,
            String createdAt,
            String updatedAt) {

        static AttemptResponse from(AiAttempt attempt) {
            return new AttemptResponse(attempt.attemptId(), attempt.messageId(), attempt.provider(),
                    attempt.state().name(), attempt.createdAt().toString(), attempt.updatedAt().toString());
        }
    }
}

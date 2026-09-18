package ai.chat2db.community.web.api.adapter.pi;

import ai.chat2db.community.tools.exception.BusinessException;
import ai.chat2db.community.tools.util.I18nUtils;
import ai.chat2db.community.web.api.controller.*;
import ai.chat2db.community.web.api.config.console.DesktopBridgeRequestContext;
import ai.chat2db.community.web.api.model.request.agent.*;
import ai.chat2db.community.web.api.model.request.ai.ModelConfigSaveRequest;
import ai.chat2db.community.web.api.model.request.ai.ParseLocalAttachmentRequest;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.validation.Validator;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;
import org.slf4j.LoggerFactory;
import org.springframework.context.i18n.LocaleContext;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;

/** A single allowlist and payload binding for all Pi client operations. */
@Component
public final class PiOperationRegistry {
    public static final String ENDPOINT = "/api/v3/ai/pi/invoke";
    public static final int PROTOCOL_VERSION = 1;
    private final ObjectMapper json;
    private final Validator validator;
    private final Map<String, Operation<?>> operations = new LinkedHashMap<>();

    public PiOperationRegistry(ObjectMapper json, Validator validator, AgentController sessions,
            AiAgentSkillController skills, AgentFeatureController features, AgentToolSettingsController tools,
            AgentToolGatewayController interaction, AgentOutputController outputs, AiChatController models) {
        this.json = json.copy().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        this.validator = validator;
        register("skills.list", PiRequests.Empty.class, p -> skills.list());
        register("runtime.list", PiRequests.Empty.class, p -> features.list());
        register("runtime.check", PiRequests.Empty.class, p -> features.checkPi());
        register("runtime.enable", AgentRuntimeEnableRequest.class, features::enablePi);
        register("runtime.disable", PiRequests.Empty.class, p -> features.disablePi());
        register("bash.check", PiRequests.Empty.class, p -> features.checkBash());
        register("bash.enable", AgentRuntimeEnableRequest.class, features::enableBash);
        register("bash.disable", PiRequests.Empty.class, p -> features.disableBash());
        register("tools.list", PiRequests.Empty.class, p -> tools.listTools());
        register("tools.setEnabled", PiRequests.ToolEnabled.class,
                p -> tools.setToolEnabled(p.toolName(), new AgentToolSettingsController.ToolEnabledRequest(p.enabled())));
        register("workspace.get", PiRequests.Empty.class, p -> tools.getSettings());
        register("workspace.set", AgentToolSettingsController.SettingsRequest.class, tools::updateSettings);
        registerDesktop("workspace.selectDirectory", PiRequests.Empty.class, p -> tools.selectDirectory());
        register("sessions.list", PiRequests.Empty.class, p -> sessions.listSessions());
        register("sessions.create", AgentSessionCreateRequest.class, sessions::createSession);
        register("sessions.get", PiRequests.SessionGet.class, p -> sessions.getSession(p.sessionId(), p.sessionVersion()));
        register("sessions.rename", PiRequests.SessionRename.class,
                p -> sessions.renameSession(p.sessionId(), new AgentSessionRenameRequest(p.title())));
        register("sessions.delete", PiRequests.Session.class, p -> sessions.deleteSession(p.sessionId()));
        register("runs.start", PiRequests.RunStart.class, p -> sessions.startRun(p.sessionId(),
                new AgentRunStartRequest(p.modelConfigId(), p.message(), p.idempotencyKey(), p.context())));
        register("runs.cancel", PiRequests.RunCancel.class,
                p -> sessions.cancelRun(p.runId(), new AgentRunCancelRequest(p.sessionId())));
        register("events.list", PiRequests.Events.class, p -> p.beforeSequence() != null
                ? sessions.listEventsBefore(p.sessionId(), p.beforeSequence(), p.limit() == null ? 200 : p.limit())
                : sessions.listEvents(p.sessionId(), p.afterSequence() == null ? 0 : p.afterSequence(),
                        p.limit() == null ? 200 : p.limit()));
        register("approvals.list", PiRequests.Session.class, p -> interaction.pending(p.sessionId()));
        register("approvals.decide", PiRequests.Decision.class, p -> interaction.decide(p.sessionId(),
                new AgentToolGatewayController.DecisionRequest(p.approvalId(), p.decision())));
        register("questions.list", PiRequests.Session.class, p -> interaction.pendingQuestions(p.sessionId()));
        register("questions.answer", PiRequests.Answer.class, p -> interaction.answerQuestion(p.sessionId(),
                new AgentToolGatewayController.QuestionAnswerRequest(p.questionId(), p.optionId(), p.text())));
        register("outputs.read", PiRequests.OutputRead.class,
                p -> outputs.read(p.sessionId(), p.artifactId(), p.cursor(), p.offset(), p.limit()));
        register("outputs.search", PiRequests.OutputSearch.class, p -> outputs.search(p.sessionId(), p.artifactId(),
                p.pattern(), p.literal() == null || p.literal(), Boolean.TRUE.equals(p.ignoreCase()), p.cursor(), p.limit()));
        registerDesktop("outputs.save", PiRequests.Output.class, p -> outputs.downloadPath(p.sessionId(), p.artifactId()));
        registerDesktop("attachments.parseLocal", ParseLocalAttachmentRequest.class, models::parseLocalAttachment);
        register("models.prepare", ModelConfigSaveRequest.class, models::saveModelConfig);
    }

    public Set<String> operationNames() { return Set.copyOf(operations.keySet()); }

    public CompletionStage<ObjectNode> invoke(JsonNode request) {
        LocaleContext requestLocale = LocaleContextHolder.getLocaleContext();
        String requestId = request == null ? "" : request.path("requestId").asText("");
        String operation = request == null ? "" : request.path("operation").asText("");
        try {
            if (request == null || !request.isObject() || !request.path("protocolVersion").isInt()
                    || request.path("protocolVersion").intValue() != PROTOCOL_VERSION
                    || requestId.isBlank() || requestId.length() > 128) {
                throw new IllegalArgumentException("Invalid Pi request envelope");
            }
            Operation<?> handler = operations.get(operation);
            if (handler == null) return CompletableFuture.completedFuture(failure(requestId,
                    "pi.unsupportedOperation", "Unsupported Pi operation: " + operation));
            Object response = handler.invoke(request.path("payload"));
            CompletionStage<?> pending = response instanceof CompletionStage<?> stage
                    ? stage : CompletableFuture.completedFuture(response);
            return pending.handle((value, error) -> {
                LocaleContext previous = LocaleContextHolder.getLocaleContext();
                try {
                    LocaleContextHolder.setLocaleContext(requestLocale);
                    return error == null ? envelope(requestId, json.valueToTree(value)) : error(requestId, operation, error);
                } finally {
                    LocaleContextHolder.setLocaleContext(previous);
                }
            });
        } catch (Exception error) {
            return CompletableFuture.completedFuture(error(requestId, operation, error));
        }
    }

    private <T> void registerDesktop(String name, Class<T> requestType, Function<T, ?> handler) {
        register(name, requestType, input -> {
            DesktopBridgeRequestContext.requireActive();
            return handler.apply(input);
        });
    }

    private <T> void register(String name, Class<T> requestType, Function<T, ?> handler) {
        if (operations.putIfAbsent(name, new Operation<>(requestType, handler)) != null) {
            throw new IllegalStateException("Duplicate Pi operation: " + name);
        }
    }

    private final class Operation<T> {
        private final Class<T> requestType;
        private final Function<T, ?> handler;
        private Operation(Class<T> requestType, Function<T, ?> handler) {
            this.requestType = requestType;
            this.handler = handler;
        }
        private Object invoke(JsonNode payload) {
            if (!payload.isObject()) throw new IllegalArgumentException("Pi payload must be an object");
            T input = json.convertValue(payload, requestType);
            var violations = validator.validate(input);
            if (!violations.isEmpty()) {
                throw new IllegalArgumentException(violations.stream().map(v -> v.getPropertyPath() + ": " + v.getMessage())
                        .sorted().findFirst().orElseThrow());
            }
            return handler.apply(input);
        }
    }

    private ObjectNode envelope(String requestId, JsonNode response) {
        ObjectNode result;
        if (response instanceof ObjectNode object) {
            result = object;
        } else {
            // A null or non-object result still needs a valid envelope instead of a class cast failure.
            result = json.createObjectNode();
            if (response == null) result.putNull("data"); else result.set("data", response);
        }
        result.put("protocolVersion", PROTOCOL_VERSION);
        result.put("requestId", requestId);
        return result;
    }

    private ObjectNode failure(String requestId, String code, String message) {
        ObjectNode result = json.createObjectNode();
        result.put("success", false).put("errorCode", code).put("errorMessage", message);
        return envelope(requestId, result);
    }

    private ObjectNode error(String requestId, String operation, Throwable error) {
        while (error instanceof CompletionException || error instanceof ExecutionException) error = error.getCause();
        if (error instanceof BusinessException business) {
            return failure(requestId, business.getCode(), I18nUtils.getMessage(business.getCode(), business.getArgs()));
        }
        if (error instanceof IllegalArgumentException) return failure(requestId, "pi.invalidRequest", error.getMessage());
        if (error instanceof SecurityException) return failure(requestId, "common.permissionDenied", error.getMessage());
        LoggerFactory.getLogger(getClass()).error("Pi operation failed: operation={}, requestId={}", operation, requestId, error);
        return failure(requestId, "common.systemError", I18nUtils.getMessage("common.systemError"));
    }
}

package ai.chat2db.community.start.ai.subscription.appserver.internal;

import ai.chat2db.community.start.ai.subscription.appserver.AppServerDisabledReason;
import ai.chat2db.community.start.ai.subscription.appserver.AppServerEventListener;
import ai.chat2db.community.start.ai.subscription.appserver.AppServerException;
import ai.chat2db.community.start.ai.subscription.appserver.AppServerProtocol;
import ai.chat2db.community.start.ai.subscription.appserver.SensitivePayloadRedactor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Bounded stdio JSON-RPC client with request correlation, allowlist enforcement, and redaction.
 * <p>
 * Bidirectional protocol: app-server may send <em>server requests</em> ({@code id}+{@code method})
 * that the client must answer. Treating those frames as orphan responses drops them and leaves
 * Codex waiting forever (no HTTP {@code tools/call}).
 * <p>
 * Response shapes differ by method. Approval requests use {@code {decision}}, while
 * {@code item/tool/requestUserInput} requires {@code {answers}} (Codex deserializes
 * {@code ToolRequestUserInputResponse}). Answering the latter with {@code decision} fails
 * deserialization, falls back to empty answers, and cancels the in-flight tool (including MCP).
 */
public final class AppServerJsonRpcClient implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(AppServerJsonRpcClient.class);

    private static final String METHOD_REQUEST_USER_INPUT = "item/tool/requestUserInput";
    private static final String METHOD_MCP_ELICITATION = "mcpServer/elicitation/request";

    /** Approval-shaped server requests that Chat2DB can auto-answer under approval_policy=never. */
    private static final Set<String> AUTO_APPROVE_METHODS = Set.of(
            "item/permissions/requestApproval");

    /** Shell/file approvals use {@code {decision}} — keep them denied. */
    private static final Set<String> AUTO_DENY_METHODS = Set.of(
            "item/commandExecution/requestApproval",
            "item/fileChange/requestApproval");

    private final ObjectMapper mapper;
    private final BoundedJsonRpcFramer framer;
    private final AtomicLong nextId = new AtomicLong(1);
    private final ConcurrentHashMap<Long, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
    private final List<AppServerEventListener> listeners = new CopyOnWriteArrayList<>();
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final Thread readerThread;
    private final long requestTimeoutMs;

    public AppServerJsonRpcClient(InputStream stdout, OutputStream stdin, ObjectMapper mapper) {
        this(stdout, stdin, mapper, AppServerProtocol.DEFAULT_MAX_MESSAGE_BYTES, 15_000L);
    }

    public AppServerJsonRpcClient(
            InputStream stdout,
            OutputStream stdin,
            ObjectMapper mapper,
            int maxMessageBytes,
            long requestTimeoutMs) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.framer = new BoundedJsonRpcFramer(stdout, stdin, maxMessageBytes);
        this.requestTimeoutMs = requestTimeoutMs;
        this.readerThread = new Thread(this::readLoop, "codex-app-server-jsonrpc-reader");
        this.readerThread.setDaemon(true);
        this.readerThread.start();
    }

    public void addListener(AppServerEventListener listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
    }

    public void removeListener(AppServerEventListener listener) {
        listeners.remove(listener);
    }

    public JsonNode request(String method, JsonNode params) {
        if (!AppServerProtocol.isAllowlistedRequest(method)) {
            throw new AppServerException(
                    AppServerDisabledReason.METHOD_NOT_ALLOWLISTED,
                    "method is not allowlisted: " + method);
        }
        if (AppServerProtocol.isDeniedNative(method)) {
            throw new AppServerException(
                    AppServerDisabledReason.METHOD_NOT_ALLOWLISTED,
                    "native capability denied: " + method);
        }
        ensureOpen();
        long id = nextId.getAndIncrement();
        ObjectNode envelope = mapper.createObjectNode();
        envelope.put("method", method);
        envelope.put("id", id);
        if (params != null && !params.isNull()) {
            envelope.set("params", params);
        } else {
            envelope.set("params", mapper.createObjectNode());
        }
        CompletableFuture<JsonNode> future = new CompletableFuture<>();
        pending.put(id, future);
        try {
            framer.writeMessage(mapper.writeValueAsString(envelope));
        } catch (IOException | AppServerException ex) {
            pending.remove(id);
            if (ex instanceof AppServerException appServerException) {
                throw appServerException;
            }
            throw new AppServerException(
                    AppServerDisabledReason.PROCESS_CRASHED,
                    "failed to write app-server request",
                    ex);
        }
        try {
            return future.get(requestTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (Exception ex) {
            pending.remove(id);
            Throwable root = ex;
            while (root.getCause() != null && root != root.getCause()) {
                if (root instanceof AppServerException appServerException) {
                    throw appServerException;
                }
                root = root.getCause();
            }
            if (root instanceof AppServerException appServerException) {
                throw appServerException;
            }
            throw new AppServerException(
                    AppServerDisabledReason.PROCESS_CRASHED,
                    "app-server request timed out or failed: " + method,
                    ex);
        }
    }

    public void notify(String method, JsonNode params) {
        if (!AppServerProtocol.ALLOWLISTED_CLIENT_NOTIFICATIONS.contains(method)) {
            throw new AppServerException(
                    AppServerDisabledReason.METHOD_NOT_ALLOWLISTED,
                    "client notification not allowlisted: " + method);
        }
        ensureOpen();
        ObjectNode envelope = mapper.createObjectNode();
        envelope.put("method", method);
        if (params != null && !params.isNull()) {
            envelope.set("params", params);
        } else {
            envelope.set("params", mapper.createObjectNode());
        }
        try {
            framer.writeMessage(mapper.writeValueAsString(envelope));
        } catch (IOException ex) {
            throw new AppServerException(
                    AppServerDisabledReason.PROCESS_CRASHED,
                    "failed to write app-server notification",
                    ex);
        }
    }

    private void readLoop() {
        try {
            while (!closed.get()) {
                String line;
                try {
                    line = framer.readMessage();
                } catch (AppServerException ex) {
                    failAll(ex);
                    return;
                }
                if (line == null) {
                    failAll(new AppServerException(
                            AppServerDisabledReason.PROCESS_CRASHED,
                            "app-server stdout closed"));
                    return;
                }
                dispatchLine(line);
            }
        } catch (IOException ex) {
            if (!closed.get()) {
                failAll(new AppServerException(
                        AppServerDisabledReason.PROCESS_CRASHED,
                        "app-server reader failed",
                        ex));
            }
        }
    }

    private void dispatchLine(String line) {
        final JsonNode node;
        try {
            node = mapper.readTree(line);
        } catch (IOException ex) {
            failAll(new AppServerException(
                    AppServerDisabledReason.MALFORMED_MESSAGE,
                    "malformed app-server JSON frame",
                    ex));
            return;
        }
        if (node == null || !node.isObject()) {
            failAll(new AppServerException(
                    AppServerDisabledReason.MALFORMED_MESSAGE,
                    "app-server frame is not a JSON object"));
            return;
        }

        JsonNode idNode = node.get("id");
        JsonNode methodNode = node.get("method");
        boolean hasMethod = methodNode != null && methodNode.isTextual();
        boolean hasResultOrError = node.has("result") || node.has("error");

        // Server → client request: { id, method, params } (must be answered).
        if (idNode != null && !idNode.isNull() && hasMethod && !hasResultOrError) {
            handleServerRequest(idNode, methodNode.asText(), node.get("params"));
            return;
        }

        // Response to a client → server request: { id, result|error }.
        if (idNode != null && !idNode.isNull()) {
            if (!idNode.isIntegralNumber() && !idNode.isInt() && !idNode.isLong()) {
                // Client only issues numeric ids; ignore mismatched response ids fail-closed for pending.
                LOG.warn("subscription app-server response with non-numeric id dropped");
                return;
            }
            long id = idNode.asLong();
            CompletableFuture<JsonNode> future = pending.remove(id);
            if (future == null) {
                LOG.warn("subscription app-server response for unknown id dropped");
                return;
            }
            if (node.has("error")) {
                JsonNode error = SensitivePayloadRedactor.redactTree(node.get("error"));
                String message = error != null && error.has("message")
                        ? SensitivePayloadRedactor.redactText(error.get("message").asText("rpc error"))
                        : "rpc error";
                future.completeExceptionally(new AppServerException(
                        AppServerDisabledReason.PROTOCOL_MISMATCH,
                        message));
                return;
            }
            JsonNode result = node.get("result");
            future.complete(result == null ? mapper.nullNode() : SensitivePayloadRedactor.redactTree(result));
            return;
        }

        // Notification: { method, params } (no id).
        if (hasMethod) {
            String method = methodNode.asText();
            JsonNode params = node.get("params");
            JsonNode redacted = SensitivePayloadRedactor.redactTree(
                    params == null ? mapper.createObjectNode() : params);
            for (AppServerEventListener listener : listeners) {
                try {
                    listener.onNotification(method, redacted);
                } catch (RuntimeException ignored) {
                    // Listener faults must not kill the protocol loop.
                }
            }
            return;
        }
        failAll(new AppServerException(
                AppServerDisabledReason.MALFORMED_MESSAGE,
                "app-server frame missing id and method"));
    }

    /**
     * Answer app-server server-requests so Codex does not hang after item/started.
     * Product policy: auto-approve MCP permission prompts; auto-answer headless user-input with
     * affirmative options; deny shell/file; accept Chat2DB MCP elicitation only.
     */
    private void handleServerRequest(JsonNode idNode, String method, JsonNode params) {
        String safeMethod = method == null ? "" : method.trim();
        LOG.info("subscription app-server serverRequest method={}", safeMethod);
        // Notify listeners for observability (redacted).
        JsonNode redactedParams = SensitivePayloadRedactor.redactTree(
                params == null ? mapper.createObjectNode() : params);
        for (AppServerEventListener listener : listeners) {
            try {
                listener.onNotification("serverRequest/" + safeMethod, redactedParams);
            } catch (RuntimeException ignored) {
                // best effort
            }
        }

        // requestUserInput must return {answers:...}, never {decision:...}.
        if (METHOD_REQUEST_USER_INPUT.equals(safeMethod)) {
            writeServerResult(idNode, buildRequestUserInputResponse(params));
            return;
        }
        // MCP elicitation uses {action, content?}, not decision.
        if (METHOD_MCP_ELICITATION.equals(safeMethod)) {
            writeServerResult(idNode, buildMcpElicitationResponse(params));
            return;
        }

        // Deny shell/file first so broad MCP heuristics cannot approve them.
        if (AUTO_DENY_METHODS.contains(safeMethod)
                || safeMethod.contains("commandExecution")
                || safeMethod.contains("fileChange")) {
            writeServerResult(idNode, decisionResult("denied"));
            return;
        }
        if (AUTO_APPROVE_METHODS.contains(safeMethod) || isMcpPermissionRequest(safeMethod, params)) {
            writeServerResult(idNode, decisionResult("approved"));
            return;
        }
        if (safeMethod.endsWith("/requestApproval")) {
            writeServerResult(idNode, decisionResult("denied"));
            return;
        }
        // Fail closed with a protocol error so Codex unblocks instead of hanging forever.
        writeServerError(idNode, -32601, "unsupported server request");
    }

    /**
     * Codex {@code ToolRequestUserInputResponse}:
     * {@code { "answers": { "<questionId>": { "answers": ["optionLabel"] } } }}.
     * Empty map is treated as cancel. Chat2DB is headless, so auto-select affirmative / first
     * option so allowlisted MCP tools are not cancelled by a wrong {@code decision} payload.
     */
    ObjectNode buildRequestUserInputResponse(JsonNode params) {
        ObjectNode result = mapper.createObjectNode();
        ObjectNode answers = mapper.createObjectNode();
        result.set("answers", answers);
        if (params == null || !params.isObject()) {
            return result;
        }
        JsonNode questions = params.get("questions");
        if (questions == null || !questions.isArray()) {
            return result;
        }
        for (JsonNode question : questions) {
            if (question == null || !question.isObject()) {
                continue;
            }
            String questionId = textOrEmpty(question.get("id"));
            if (questionId.isEmpty()) {
                continue;
            }
            // Never auto-fill secret prompts with placeholder text.
            if (question.path("isSecret").asBoolean(false)) {
                continue;
            }
            String selected = selectUserInputAnswer(question);
            if (selected == null || selected.isBlank()) {
                continue;
            }
            ObjectNode answer = mapper.createObjectNode();
            ArrayNode values = mapper.createArrayNode();
            values.add(selected);
            answer.set("answers", values);
            answers.set(questionId, answer);
        }
        LOG.info(
                "subscription app-server requestUserInput auto-answers count={}",
                answers.size());
        return result;
    }

    /**
     * Prefer affirmative option labels; otherwise first option label; free-form falls back to yes.
     */
    static String selectUserInputAnswer(JsonNode question) {
        if (question == null || !question.isObject()) {
            return null;
        }
        JsonNode options = question.get("options");
        if (options != null && options.isArray() && options.size() > 0) {
            String affirmative = null;
            String first = null;
            for (JsonNode option : options) {
                if (option == null || !option.isObject()) {
                    continue;
                }
                String label = textOrEmpty(option.get("label"));
                if (label.isEmpty()) {
                    continue;
                }
                if (first == null) {
                    first = label;
                }
                if (isAffirmativeLabel(label) || isAffirmativeLabel(textOrEmpty(option.get("description")))) {
                    affirmative = label;
                    break;
                }
            }
            return affirmative != null ? affirmative : first;
        }
        // Free-form / isOther-only: Codex test client uses user_note: prefix for free text.
        return "yes";
    }

    private static boolean isAffirmativeLabel(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        return lower.contains("yes")
                || lower.contains("allow")
                || lower.contains("approve")
                || lower.contains("accept")
                || lower.contains("continue")
                || lower.contains("proceed")
                || lower.contains("confirm")
                || lower.contains("recommended")
                || lower.contains("ok");
    }

    /**
     * {@code McpServerElicitationRequestResponse}: accept Chat2DB MCP only; decline others.
     */
    ObjectNode buildMcpElicitationResponse(JsonNode params) {
        ObjectNode result = mapper.createObjectNode();
        if (isChat2dbMcpContext(params)) {
            result.put("action", "accept");
            result.set("content", mapper.createObjectNode());
        } else {
            result.put("action", "decline");
            result.putNull("content");
        }
        return result;
    }

    private static boolean isChat2dbMcpContext(JsonNode params) {
        if (params == null || params.isNull()) {
            return false;
        }
        String blob = params.toString().toLowerCase(Locale.ROOT);
        return blob.contains("chat2db_subscription")
                || blob.contains("mcp__chat2db_subscription");
    }

    private static boolean isMcpPermissionRequest(String method, JsonNode params) {
        if (method == null) {
            return false;
        }
        String lower = method.toLowerCase(Locale.ROOT);
        if (lower.contains("mcp") || lower.contains("permission")) {
            return true;
        }
        if (params == null || !params.isObject()) {
            return false;
        }
        String blob = params.toString().toLowerCase(Locale.ROOT);
        return blob.contains("chat2db_subscription")
                || blob.contains("mcp_tool")
                || blob.contains("mcp__")
                || blob.contains("network");
    }

    private ObjectNode decisionResult(String decision) {
        ObjectNode result = mapper.createObjectNode();
        result.put("decision", decision);
        return result;
    }

    private static String textOrEmpty(JsonNode node) {
        if (node == null || node.isNull() || !node.isTextual()) {
            return "";
        }
        return node.asText("").trim();
    }

    private void writeServerResult(JsonNode idNode, JsonNode result) {
        if (closed.get()) {
            return;
        }
        ObjectNode envelope = mapper.createObjectNode();
        setId(envelope, idNode);
        envelope.set("result", result == null ? mapper.nullNode() : result);
        try {
            framer.writeMessage(mapper.writeValueAsString(envelope));
        } catch (IOException | AppServerException ex) {
            LOG.warn("subscription app-server failed to answer serverRequest");
        }
    }

    private void writeServerError(JsonNode idNode, int code, String message) {
        if (closed.get()) {
            return;
        }
        ObjectNode envelope = mapper.createObjectNode();
        setId(envelope, idNode);
        ObjectNode error = mapper.createObjectNode();
        error.put("code", code);
        error.put("message", message == null ? "error" : message);
        envelope.set("error", error);
        try {
            framer.writeMessage(mapper.writeValueAsString(envelope));
        } catch (IOException | AppServerException ex) {
            LOG.warn("subscription app-server failed to error serverRequest");
        }
    }

    private static void setId(ObjectNode envelope, JsonNode idNode) {
        if (idNode == null || idNode.isNull()) {
            envelope.putNull("id");
        } else if (idNode.isIntegralNumber()) {
            envelope.put("id", idNode.asLong());
        } else if (idNode.isTextual()) {
            envelope.put("id", idNode.asText());
        } else if (idNode.isFloatingPointNumber()) {
            envelope.put("id", idNode.asDouble());
        } else {
            envelope.set("id", idNode);
        }
    }

    private void failAll(AppServerException error) {
        for (CompletableFuture<JsonNode> future : pending.values()) {
            future.completeExceptionally(error);
        }
        pending.clear();
    }

    private void ensureOpen() {
        if (closed.get()) {
            throw new AppServerException(
                    AppServerDisabledReason.SHUTDOWN,
                    "app-server client is closed");
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        failAll(new AppServerException(AppServerDisabledReason.SHUTDOWN, "app-server client closed"));
        try {
            framer.close();
        } catch (IOException ignored) {
            // best effort
        }
        readerThread.interrupt();
    }
}

package ai.chat2db.community.start.ai.subscription.appserver;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiFunction;

/**
 * Contract fixture that speaks official-style newline-delimited app-server JSON-RPC.
 * Omits the jsonrpc header on the wire, matching public app-server docs.
 * <p>
 * Close protocol: callers must {@link #requestStop()}, close underlying pipes (to unblock
 * {@code readLine}), join this thread, then call {@link #close()}. Closing the reader while
 * {@code readLine} holds its monitor deadlocks.
 */
public final class FakeAppServer implements Runnable, AutoCloseable {

    private final ObjectMapper mapper = new ObjectMapper();
    private final BufferedReader reader;
    private final BufferedWriter writer;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final Map<String, BiFunction<Long, JsonNode, JsonNode>> handlers = new ConcurrentHashMap<>();
    private boolean includeSecretInLoginCompleted;
    private boolean omitPlatformFields;
    /** Official initialize userAgent; must embed a semantic version for the integrity gate. */
    private volatile String userAgent = "codex_app_server/1.0.0";

    public FakeAppServer(InputStream in, OutputStream out) {
        this.reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        this.writer = new BufferedWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8));
        registerDefaults();
    }

    public void requestStop() {
        running.set(false);
    }

    public void setIncludeSecretInLoginCompleted(boolean includeSecretInLoginCompleted) {
        this.includeSecretInLoginCompleted = includeSecretInLoginCompleted;
    }

    public void setOmitPlatformFields(boolean omitPlatformFields) {
        this.omitPlatformFields = omitPlatformFields;
    }

    public void setUserAgent(String userAgent) {
        this.userAgent = userAgent;
    }

    public void on(String method, BiFunction<Long, JsonNode, JsonNode> handler) {
        handlers.put(method, handler);
    }

    private void registerDefaults() {
        on("initialize", (id, params) -> {
            ObjectNode result = mapper.createObjectNode();
            // Official initialize returns userAgent (with version), codexHome, platform*.
            if (userAgent != null) {
                result.put("userAgent", userAgent);
            }
            if (!omitPlatformFields) {
                result.put("codexHome", "/tmp/fake-codex-home");
                result.put("platformFamily", "unix");
                result.put("platformOs", "macos");
            }
            return result;
        });
        on("account/read", (id, params) -> {
            ObjectNode result = mapper.createObjectNode();
            ObjectNode account = mapper.createObjectNode();
            account.put("type", "chatgpt");
            account.put("email", "user@example.com");
            account.put("planType", "plus");
            // Deliberately include a secret field to prove redaction on the client side.
            account.put("accessToken", "sk-secret-should-never-leak");
            result.set("account", account);
            result.put("requiresOpenaiAuth", true);
            return result;
        });
        on("account/login/start", (id, params) -> {
            ObjectNode result = mapper.createObjectNode();
            String type = params.path("type").asText("chatgpt");
            result.put("type", type);
            result.put("loginId", "login-1");
            if ("chatgpt".equals(type)) {
                result.put("authUrl", "https://chatgpt.com/oauth?state=abc");
            } else {
                result.put("verificationUrl", "https://auth.openai.com/codex/device");
                result.put("userCode", "ABCD-1234");
            }
            return result;
        });
        on("account/login/cancel", (id, params) -> mapper.createObjectNode());
        on("account/logout", (id, params) -> mapper.createObjectNode());
        on("model/list", (id, params) -> {
            ObjectNode result = mapper.createObjectNode();
            ObjectNode model = mapper.createObjectNode();
            model.put("id", "gpt-5.4");
            model.put("displayName", "GPT-5.4");
            model.put("hidden", false);
            model.put("isDefault", true);
            model.put("defaultReasoningEffort", "medium");
            var efforts = model.putArray("supportedReasoningEfforts");
            efforts.addObject().put("reasoningEffort", "low").put("description", "Fast");
            efforts.addObject().put("reasoningEffort", "high").put("description", "Deep");
            efforts.addObject().put("reasoningEffort", "xhigh").put("description", "Deeper");
            result.putArray("data").add(model);
            result.putNull("nextCursor");
            return result;
        });
        on("thread/start", (id, params) -> {
            lastThreadStartParams = params.deepCopy();
            ObjectNode result = mapper.createObjectNode();
            ObjectNode thread = mapper.createObjectNode();
            thread.put("id", "thr_1");
            thread.put("sessionId", "thr_1");
            result.set("thread", thread);
            return result;
        });
        on("thread/resume", (id, params) -> {
            ObjectNode result = mapper.createObjectNode();
            ObjectNode thread = mapper.createObjectNode();
            thread.put("id", params.path("threadId").asText());
            thread.put("sessionId", params.path("threadId").asText());
            result.set("thread", thread);
            return result;
        });
        on("thread/read", (id, params) -> {
            ObjectNode result = mapper.createObjectNode();
            ObjectNode thread = mapper.createObjectNode();
            thread.put("id", params.path("threadId").asText());
            thread.put("sessionId", params.path("threadId").asText());
            result.set("thread", thread);
            return result;
        });
        on("turn/start", (id, params) -> {
            lastTurnStartParams = params.deepCopy();
            ObjectNode result = mapper.createObjectNode();
            ObjectNode turn = mapper.createObjectNode();
            turn.put("id", "turn_1");
            turn.put("status", "inProgress");
            result.set("turn", turn);
            return result;
        });
        on("turn/interrupt", (id, params) -> mapper.createObjectNode());
    }

    private volatile JsonNode lastThreadStartParams;
    private volatile JsonNode lastTurnStartParams;
    private volatile JsonNode lastClientResponseToServerRequest;

    JsonNode lastThreadStartParams() {
        return lastThreadStartParams;
    }

    JsonNode lastTurnStartParams() {
        return lastTurnStartParams;
    }

    JsonNode lastClientResponseToServerRequest() {
        return lastClientResponseToServerRequest;
    }

    /** Push a server→client request that the production client must answer (not drop). */
    public synchronized void pushServerRequest(long id, String method, JsonNode params) throws IOException {
        ObjectNode envelope = mapper.createObjectNode();
        envelope.put("id", id);
        envelope.put("method", method);
        envelope.set("params", params == null ? mapper.createObjectNode() : params);
        writer.write(mapper.writeValueAsString(envelope));
        writer.write('\n');
        writer.flush();
    }

    @Override
    public void run() {
        try {
            while (running.get()) {
                String line = reader.readLine();
                if (line == null) {
                    return;
                }
                if (!running.get()) {
                    return;
                }
                JsonNode node = mapper.readTree(line);
                if (!node.has("id")) {
                    // notification (e.g. initialized) — ignore
                    continue;
                }
                // Client response to a server request: { id, result|error } without method.
                if (!node.has("method") && (node.has("result") || node.has("error"))) {
                    lastClientResponseToServerRequest = node.deepCopy();
                    continue;
                }
                long id = node.get("id").asLong();
                String method = node.path("method").asText();
                JsonNode params = node.get("params");
                BiFunction<Long, JsonNode, JsonNode> handler = handlers.get(method);
                if (handler == null) {
                    writeError(id, -32601, "Method not found: " + method);
                    continue;
                }
                JsonNode result = handler.apply(id, params == null ? mapper.createObjectNode() : params);
                writeResult(id, result);
                if ("account/login/start".equals(method)) {
                    ObjectNode completed = mapper.createObjectNode();
                    completed.put("method", "account/login/completed");
                    ObjectNode p = mapper.createObjectNode();
                    p.put("loginId", "login-1");
                    p.put("success", true);
                    p.putNull("error");
                    if (includeSecretInLoginCompleted) {
                        p.put("refreshToken", "refresh-secret-token");
                    }
                    completed.set("params", p);
                    writer.write(mapper.writeValueAsString(completed));
                    writer.write('\n');
                    writer.flush();
                }
            }
        } catch (IOException ignored) {
            // peer closed pipe ends — expected during destroyForcibly
        }
    }

    private void writeResult(long id, JsonNode result) throws IOException {
        ObjectNode envelope = mapper.createObjectNode();
        envelope.put("id", id);
        envelope.set("result", result);
        writer.write(mapper.writeValueAsString(envelope));
        writer.write('\n');
        writer.flush();
    }

    private void writeError(long id, int code, String message) throws IOException {
        ObjectNode envelope = mapper.createObjectNode();
        envelope.put("id", id);
        ObjectNode error = mapper.createObjectNode();
        error.put("code", code);
        error.put("message", message);
        envelope.set("error", error);
        writer.write(mapper.writeValueAsString(envelope));
        writer.write('\n');
        writer.flush();
    }

    @Override
    public void close() {
        running.set(false);
        // Do not close reader/writer while run() may still hold the reader monitor.
        // PipeManagedProcess closes pipes and joins this thread first; closing here is
        // a best-effort cleanup after join.
        try {
            writer.close();
        } catch (IOException ignored) {
        }
        try {
            reader.close();
        } catch (IOException ignored) {
        }
    }
}

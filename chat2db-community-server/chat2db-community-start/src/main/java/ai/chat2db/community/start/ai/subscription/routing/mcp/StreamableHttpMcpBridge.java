package ai.chat2db.community.start.ai.subscription.routing.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import ai.chat2db.community.start.ai.subscription.appserver.Chat2dbMcpToolPolicy;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Minimal MCP Streamable HTTP bridge:
 * <ul>
 *   <li>Binds {@code 127.0.0.1:0}</li>
 *   <li>≥256-bit in-process capability via {@value #CAPABILITY_HEADER}</li>
 *   <li>JSON-RPC: {@code initialize}, {@code notifications/initialized},
 *       {@code tools/list}, {@code tools/call}, and empty resource discovery</li>
 *   <li>{@code tools/call} requires a bound active attempt and delegates to
 *       {@link McpToolCallHandler}</li>
 * </ul>
 * Does not log request parameters or tool result bodies.
 */
public final class StreamableHttpMcpBridge implements DedicatedMcpBridge {

    private static final Logger LOG = LoggerFactory.getLogger(StreamableHttpMcpBridge.class);

    public static final String CAPABILITY_HEADER = "X-Chat2DB-MCP-Capability";
    public static final String MCP_PATH = "/mcp";
    public static final int MAX_BODY_BYTES = 65_536;
    public static final int CAPABILITY_BYTES = 32; // 256-bit
    public static final String PROTOCOL_VERSION = "2024-11-05";
    /** Stable path under Community storage so diagnostics are findable without logback path quirks. */
    static final Path DIAGNOSTIC_LOG_PATH = Path.of(
            System.getProperty("user.home"), ".chat2db-community", "logs", "mcp-bridge.log");

    private static final Set<String> DEFAULT_TOOLS = Chat2dbMcpToolPolicy.DATABASE_TOOLS;

    private final boolean featureEnabled;
    private final McpToolCallHandler toolCallHandler;
    private final Set<String> allowlist;
    private final ObjectMapper mapper;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicReference<String> capability = new AtomicReference<>();
    private final AtomicReference<String> activeAttemptId = new AtomicReference<>();
    private final AtomicReference<String> disabledReason = new AtomicReference<>("NOT_STARTED");

    private HttpServer httpServer;

    public StreamableHttpMcpBridge(boolean featureEnabled, McpToolCallHandler toolCallHandler) {
        this(featureEnabled, toolCallHandler, DEFAULT_TOOLS, new ObjectMapper());
    }

    public StreamableHttpMcpBridge(
            boolean featureEnabled,
            McpToolCallHandler toolCallHandler,
            Set<String> allowlist,
            ObjectMapper mapper) {
        this.featureEnabled = featureEnabled;
        this.toolCallHandler = Objects.requireNonNull(toolCallHandler, "toolCallHandler");
        this.allowlist = Set.copyOf(Objects.requireNonNull(allowlist, "allowlist"));
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    @Override
    public boolean isEnabled() {
        return featureEnabled && running.get() && httpServer != null;
    }

    @Override
    public Optional<String> disabledReason() {
        if (isEnabled()) {
            return Optional.empty();
        }
        if (!featureEnabled) {
            return Optional.of("FEATURE_DISABLED");
        }
        return Optional.ofNullable(disabledReason.get());
    }

    @Override
    public synchronized void start() {
        if (running.get()) {
            return;
        }
        if (!featureEnabled) {
            disabledReason.set("FEATURE_DISABLED");
            return;
        }
        try {
            byte[] secret = new byte[CAPABILITY_BYTES];
            new SecureRandom().nextBytes(secret);
            capability.set(HexFormat.of().formatHex(secret));

            HttpServer server = HttpServer.create(
                    new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
            server.createContext(MCP_PATH, this::handle);
            server.setExecutor(Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(r, "subscription-mcp-http");
                t.setDaemon(true);
                return t;
            }));
            server.start();
            this.httpServer = server;
            running.set(true);
            disabledReason.set(null);
        } catch (IOException ex) {
            capability.set(null);
            disabledReason.set("LOOPBACK_BIND_FAILED");
            running.set(false);
            this.httpServer = null;
        }
    }

    @Override
    public synchronized void stop() {
        running.set(false);
        capability.set(null);
        activeAttemptId.set(null);
        if (httpServer != null) {
            httpServer.stop(0);
            httpServer = null;
        }
        disabledReason.set("STOPPED");
    }

    @Override
    public Optional<InetSocketAddress> boundAddress() {
        if (httpServer == null) {
            return Optional.empty();
        }
        InetSocketAddress addr = httpServer.getAddress();
        return Optional.of(new InetSocketAddress("127.0.0.1", addr.getPort()));
    }

    @Override
    public Optional<String> capabilityToken() {
        return Optional.ofNullable(capability.get());
    }

    @Override
    public void bindActiveAttempt(String attemptId) {
        if (attemptId == null || attemptId.isBlank()) {
            throw new IllegalArgumentException("attemptId required");
        }
        activeAttemptId.set(attemptId);
    }

    @Override
    public void clearActiveAttempt() {
        activeAttemptId.set(null);
    }

    @Override
    public Optional<String> activeAttemptId() {
        return Optional.ofNullable(activeAttemptId.get());
    }

    private void handle(HttpExchange exchange) throws IOException {
        long startedNanos = System.nanoTime();
        String method = null;
        String toolName = null;
        int httpStatus = 500;
        String outcome = "error";
        try {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                httpStatus = 405;
                outcome = "method_not_allowed";
                writeText(exchange, 405, "method not allowed");
                return;
            }
            if (!running.get()) {
                httpStatus = 503;
                outcome = "bridge_not_running";
                writeText(exchange, 503, "bridge not running");
                return;
            }
            if (!capabilityMatches(exchange.getRequestHeaders())) {
                httpStatus = 401;
                outcome = "invalid_capability";
                // Do not log tokens or headers; only that auth failed.
                LOG.warn("subscription mcp rejected capability auth hasActiveAttempt={}",
                        activeAttemptId.get() != null);
                writeText(exchange, 401, "invalid capability");
                return;
            }

            byte[] body = readBodyBounded(exchange.getRequestBody(), MAX_BODY_BYTES);
            if (body == null) {
                httpStatus = 413;
                outcome = "body_too_large";
                writeText(exchange, 413, "body too large");
                return;
            }
            if (body.length == 0) {
                httpStatus = 400;
                outcome = "empty_body";
                writeText(exchange, 400, "empty body");
                return;
            }

            final JsonNode root;
            try {
                root = mapper.readTree(body);
            } catch (IOException ex) {
                httpStatus = 400;
                outcome = "malformed_json";
                writeText(exchange, 400, "malformed json");
                return;
            }
            if (root == null || !root.isObject()) {
                httpStatus = 400;
                outcome = "json_object_required";
                writeText(exchange, 400, "json object required");
                return;
            }

            method = text(root, "method");
            JsonNode idNode = root.get("id");
            boolean notification = idNode == null || idNode.isNull();
            JsonNode params = root.get("params");

            if (method == null || method.isBlank()) {
                httpStatus = 200;
                outcome = "invalid_request";
                writeJson(exchange, 200, errorResponse(idNode, -32600, "Invalid Request"));
                return;
            }

            switch (method) {
                case "initialize" -> {
                    if (notification) {
                        httpStatus = 400;
                        outcome = "initialize_requires_id";
                        writeText(exchange, 400, "initialize requires id");
                        return;
                    }
                    httpStatus = 200;
                    outcome = "ok";
                    writeJson(exchange, 200, initializeResult(idNode));
                }
                case "notifications/initialized" -> {
                    // Notification: accepted, no JSON-RPC body.
                    httpStatus = 202;
                    outcome = "ok";
                    writeEmpty(exchange, 202);
                }
                case "tools/list" -> {
                    if (notification) {
                        httpStatus = 400;
                        outcome = "tools_list_requires_id";
                        writeText(exchange, 400, "tools/list requires id");
                        return;
                    }
                    httpStatus = 200;
                    outcome = "ok toolCount=" + allowlist.size();
                    writeJson(exchange, 200, toolsListResult(idNode));
                }
                case "resources/list" -> {
                    httpStatus = 200;
                    outcome = "ok_empty";
                    writeJson(exchange, 200, emptyCollectionResult(idNode, "resources"));
                }
                case "resources/templates/list" -> {
                    httpStatus = 200;
                    outcome = "ok_empty";
                    writeJson(exchange, 200,
                            emptyCollectionResult(idNode, "resourceTemplates"));
                }
                case "tools/call" -> {
                    if (notification) {
                        httpStatus = 400;
                        outcome = "tools_call_requires_id";
                        writeText(exchange, 400, "tools/call requires id");
                        return;
                    }
                    toolName = params != null && params.isObject() ? text(params, "name") : null;
                    boolean hasLease = activeAttemptId.get() != null && !activeAttemptId.get().isBlank();
                    String begin = "subscription mcp tools/call begin tool="
                            + sanitizeToolName(toolName)
                            + " hasActiveAttempt=" + hasLease
                            + " allowlisted=" + (toolName != null && allowlist.contains(toolName));
                    LOG.info(begin);
                    appendDiagnosticLine(begin);
                    ObjectNode callResponse = toolsCallResult(idNode, params);
                    httpStatus = 200;
                    outcome = summarizeToolsCallOutcome(callResponse, toolName);
                    writeJson(exchange, 200, callResponse);
                }
                default -> {
                    httpStatus = 200;
                    outcome = "method_not_found";
                    writeJson(exchange, 200, errorResponse(idNode, -32601, "Method not found"));
                }
            }
        } finally {
            long elapsedMs = (System.nanoTime() - startedNanos) / 1_000_000L;
            // Diagnostics only: method, status, outcome, timing. Never log args/bodies/tokens.
            if ("tools/call".equals(method) || "tools/list".equals(method)
                    || "initialize".equals(method)
                    || httpStatus >= 400
                    || (outcome != null && !outcome.startsWith("ok"))) {
                String line = "subscription mcp request method="
                        + (method == null ? "-" : method)
                        + " tool=" + sanitizeToolName(toolName)
                        + " httpStatus=" + httpStatus
                        + " outcome=" + outcome
                        + " elapsedMs=" + elapsedMs
                        + " hasActiveAttempt=" + (activeAttemptId.get() != null);
                LOG.info(line);
                appendDiagnosticLine(line);
            }
            exchange.close();
        }
    }

    /** Best-effort dual-write so MCP diagnostics are always under ~/.chat2db-community/logs. */
    static void appendDiagnosticLine(String line) {
        if (line == null || line.isBlank()) {
            return;
        }
        try {
            Path parent = DIAGNOSTIC_LOG_PATH.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            // Cap growth: if the file is huge, truncate by rewriting last portion is overkill;
            // daily restarts + INFO volume stay small. No secrets in line content by contract.
            String stamped = Instant.now() + " " + line + System.lineSeparator();
            Files.writeString(DIAGNOSTIC_LOG_PATH, stamped,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {
            // Diagnostics must never fail the MCP request path.
        }
    }

    private static String sanitizeToolName(String toolName) {
        if (toolName == null || toolName.isBlank()) {
            return "-";
        }
        // Bound + strip control chars; never log arguments.
        String trimmed = toolName.trim();
        if (trimmed.length() > 64) {
            trimmed = trimmed.substring(0, 64);
        }
        return trimmed.replaceAll("[^a-zA-Z0-9_.-]", "_");
    }

    private static String summarizeToolsCallOutcome(ObjectNode callResponse, String toolName) {
        if (callResponse == null) {
            return "null_response tool=" + sanitizeToolName(toolName);
        }
        JsonNode error = callResponse.get("error");
        if (error != null && error.isObject()) {
            String code = error.path("code").asText("?");
            String message = error.path("message").asText("?");
            // Messages are fixed machine codes from this bridge, not user/tool bodies.
            return "rpc_error code=" + code + " message=" + message + " tool=" + sanitizeToolName(toolName);
        }
        JsonNode result = callResponse.get("result");
        if (result != null && result.isObject()) {
            boolean isError = result.path("isError").asBoolean(false);
            return (isError ? "tool_error" : "tool_ok") + " tool=" + sanitizeToolName(toolName);
        }
        return "unknown tool=" + sanitizeToolName(toolName);
    }

    private ObjectNode initializeResult(JsonNode idNode) {
        ObjectNode response = mapper.createObjectNode();
        response.put("jsonrpc", "2.0");
        setId(response, idNode);
        ObjectNode result = mapper.createObjectNode();
        result.put("protocolVersion", PROTOCOL_VERSION);
        ObjectNode capabilities = mapper.createObjectNode();
        capabilities.set("tools", mapper.createObjectNode());
        result.set("capabilities", capabilities);
        ObjectNode serverInfo = mapper.createObjectNode();
        serverInfo.put("name", "chat2db-subscription-mcp");
        serverInfo.put("version", "0.1.0");
        result.set("serverInfo", serverInfo);
        response.set("result", result);
        return response;
    }

    private ObjectNode toolsListResult(JsonNode idNode) {
        ObjectNode response = mapper.createObjectNode();
        response.put("jsonrpc", "2.0");
        setId(response, idNode);
        ObjectNode result = mapper.createObjectNode();
        ArrayNode tools = mapper.createArrayNode();
        for (String name : allowlist) {
            ObjectNode tool = mapper.createObjectNode();
            tool.put("name", name);
            tool.put("description", toolDescription(name));
            tool.set("inputSchema", toolSchema(name));
            tools.add(tool);
        }
        result.set("tools", tools);
        response.set("result", result);
        return response;
    }

    private ObjectNode emptyCollectionResult(JsonNode idNode, String field) {
        ObjectNode response = mapper.createObjectNode();
        response.put("jsonrpc", "2.0");
        setId(response, idNode);
        ObjectNode result = mapper.createObjectNode();
        result.set(field, mapper.createArrayNode());
        response.set("result", result);
        return response;
    }

    private String toolDescription(String name) {
        return switch (name) {
            case "list_all_datasources" -> "List available Chat2DB datasources before datasource-scoped calls.";
            case "list_all_tables" -> "List tables in a target datasource database and optional schema.";
            case "list_all_databases" -> "List databases on a target Chat2DB datasource.";
            case "list_all_schemas" -> "List schemas in a target datasource database.";
            case "execute_sql" -> "Execute SQL against an explicitly selected Chat2DB datasource and database.";
            case "get_tables_schema" -> "Get schema details for explicitly named tables.";
            case "text2sql" -> "Convert a natural-language question into SQL with optional datasource context.";
            default -> "Chat2DB allowlisted tool.";
        };
    }

    private ObjectNode toolSchema(String name) {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        schema.put("additionalProperties", false);
        ObjectNode properties = mapper.createObjectNode();
        ArrayNode required = mapper.createArrayNode();
        switch (name) {
            case "list_all_datasources" -> { }
            case "list_all_databases" -> {
                addInteger(properties, "dataSourceId", "Datasource id returned by list_all_datasources.");
                required.add("dataSourceId");
            }
            case "list_all_tables" -> {
                addInteger(properties, "dataSourceId", "Datasource id returned by list_all_datasources.");
                addString(properties, "databaseName", "Target database name.");
                addString(properties, "schemaName", "Optional target schema name.");
                required.add("dataSourceId");
                required.add("databaseName");
            }
            case "list_all_schemas" -> {
                addString(properties, "targetDatabaseName", "Target database name.");
                addInteger(properties, "dataSourceId", "Datasource id returned by list_all_datasources.");
                required.add("targetDatabaseName");
                required.add("dataSourceId");
            }
            case "execute_sql" -> {
                addString(properties, "sql", "SQL statement to execute.");
                addInteger(properties, "pageSize", "Optional SELECT page size, default 200 and maximum 500.");
                addInteger(properties, "dataSourceId", "Datasource id returned by list_all_datasources.");
                addString(properties, "databaseName", "Target database name.");
                addString(properties, "schemaName", "Optional target schema name.");
                required.add("sql");
                required.add("dataSourceId");
                required.add("databaseName");
            }
            case "get_tables_schema" -> {
                ObjectNode tableNames = mapper.createObjectNode();
                tableNames.put("type", "array");
                tableNames.put("description", "Table names to inspect.");
                tableNames.put("minItems", 1);
                tableNames.set("items", mapper.createObjectNode().put("type", "string"));
                properties.set("tableNames", tableNames);
                addInteger(properties, "dataSourceId", "Datasource id returned by list_all_datasources.");
                addString(properties, "databaseName", "Target database name.");
                addString(properties, "schemaName", "Optional target schema name.");
                required.add("tableNames");
                required.add("dataSourceId");
                required.add("databaseName");
            }
            case "text2sql" -> {
                addString(properties, "question", "Natural-language question to convert to SQL.");
                addInteger(properties, "dataSourceId", "Optional datasource id.");
                addString(properties, "databaseName", "Optional target database name.");
                addString(properties, "schemaName", "Optional target schema name.");
                required.add("question");
            }
            default -> { }
        }
        schema.set("properties", properties);
        schema.set("required", required);
        return schema;
    }

    private void addString(ObjectNode properties, String name, String description) {
        ObjectNode property = mapper.createObjectNode();
        property.put("type", "string");
        property.put("description", description);
        properties.set(name, property);
    }

    private void addInteger(ObjectNode properties, String name, String description) {
        ObjectNode property = mapper.createObjectNode();
        property.put("type", "integer");
        property.put("description", description);
        properties.set(name, property);
    }

    private ObjectNode toolsCallResult(JsonNode idNode, JsonNode params) {
        String attemptId = activeAttemptId.get();
        if (attemptId == null || attemptId.isBlank()) {
            return errorResponse(idNode, -32001, "NO_ACTIVE_ATTEMPT_LEASE");
        }
        if (params == null || !params.isObject()) {
            return errorResponse(idNode, -32602, "Invalid params");
        }
        String toolName = text(params, "name");
        if (toolName == null || toolName.isBlank()) {
            return errorResponse(idNode, -32602, "tool name required");
        }
        if (!allowlist.contains(toolName)) {
            return errorResponse(idNode, -32602, "TOOL_NOT_ALLOWLISTED");
        }
        JsonNode arguments = params.get("arguments");
        String argumentsJson;
        try {
            argumentsJson = arguments == null || arguments.isNull()
                    ? "{}"
                    : mapper.writeValueAsString(arguments);
        } catch (IOException ex) {
            return errorResponse(idNode, -32602, "Invalid arguments");
        }

        McpToolCallHandler.McpToolCallResult callResult;
        try {
            callResult = toolCallHandler.call(attemptId, toolName, argumentsJson);
        } catch (RuntimeException exception) {
            activeAttemptId.compareAndSet(attemptId, null);
            callResult = McpToolCallHandler.McpToolCallResult.error(
                    "TOOL_OUTCOME_UNKNOWN_LEDGER_UNAVAILABLE");
        }

        ObjectNode response = mapper.createObjectNode();
        response.put("jsonrpc", "2.0");
        setId(response, idNode);
        ObjectNode result = mapper.createObjectNode();
        ArrayNode content = mapper.createArrayNode();
        ObjectNode text = mapper.createObjectNode();
        text.put("type", "text");
        if (callResult.success()) {
            // The response is transient and required by the model. Implementations journal only
            // a digest/reference; this bridge never logs or persists the response body.
            text.put("text", callResult.responseText() == null ? "" : callResult.responseText());
            result.put("isError", false);
        } else {
            text.put("text", callResult.errorCode() == null ? "TOOL_ERROR" : callResult.errorCode());
            result.put("isError", true);
        }
        content.add(text);
        result.set("content", content);
        response.set("result", result);
        return response;
    }

    private boolean capabilityMatches(Headers headers) {
        String expected = capability.get();
        if (expected == null) {
            return false;
        }
        String provided = headers.getFirst(CAPABILITY_HEADER);
        if (provided == null) {
            // Also accept Authorization: Bearer <token> for MCP-style clients.
            String auth = headers.getFirst("Authorization");
            if (auth != null && auth.regionMatches(true, 0, "Bearer ", 0, 7)) {
                provided = auth.substring(7).trim();
            }
        }
        if (provided == null) {
            return false;
        }
        byte[] a = expected.getBytes(StandardCharsets.UTF_8);
        byte[] b = provided.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(a, b);
    }

    /**
     * @return body bytes, or {@code null} if the body exceeds {@code maxBytes}
     */
    static byte[] readBodyBounded(InputStream in, int maxBytes) throws IOException {
        byte[] buffer = new byte[Math.min(8192, maxBytes + 1)];
        int total = 0;
        byte[] accumulated = new byte[maxBytes + 1];
        int read;
        while ((read = in.read(buffer)) != -1) {
            if (total + read > maxBytes) {
                // Drain remainder then reject.
                while (in.read(buffer) != -1) {
                    // discard
                }
                return null;
            }
            System.arraycopy(buffer, 0, accumulated, total, read);
            total += read;
        }
        byte[] body = new byte[total];
        System.arraycopy(accumulated, 0, body, 0, total);
        return body;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || !value.isTextual()) {
            return null;
        }
        return value.asText();
    }

    private void setId(ObjectNode response, JsonNode idNode) {
        if (idNode == null || idNode.isNull()) {
            response.putNull("id");
        } else if (idNode.isIntegralNumber()) {
            response.put("id", idNode.asLong());
        } else if (idNode.isFloatingPointNumber()) {
            response.put("id", idNode.asDouble());
        } else {
            response.put("id", idNode.asText());
        }
    }

    private ObjectNode errorResponse(JsonNode idNode, int code, String message) {
        ObjectNode response = mapper.createObjectNode();
        response.put("jsonrpc", "2.0");
        setId(response, idNode);
        ObjectNode error = mapper.createObjectNode();
        error.put("code", code);
        error.put("message", message);
        response.set("error", error);
        return response;
    }

    private void writeJson(HttpExchange exchange, int status, ObjectNode body) throws IOException {
        byte[] bytes = mapper.writeValueAsBytes(body);
        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private void writeText(HttpExchange exchange, int status, String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", "text/plain; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private void writeEmpty(HttpExchange exchange, int status) throws IOException {
        exchange.sendResponseHeaders(status, -1);
    }
}

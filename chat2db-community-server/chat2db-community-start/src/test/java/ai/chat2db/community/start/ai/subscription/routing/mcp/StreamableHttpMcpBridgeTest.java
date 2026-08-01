package ai.chat2db.community.start.ai.subscription.routing.mcp;

import ai.chat2db.community.start.ai.subscription.runtime.SubscriptionMcpToolExecutor;
import ai.chat2db.community.web.api.mcp.adapter.AiToolMcpAdapter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

@Timeout(value = 30, unit = TimeUnit.SECONDS)
class StreamableHttpMcpBridgeTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private StreamableHttpMcpBridge bridge;

    @AfterEach
    void tearDown() {
        if (bridge != null) {
            bridge.stop();
        }
    }

    @Test
    void initializeToolsListAndCallSucceedWithCapabilityAndLease() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<String> seenAttempt = new AtomicReference<>();
        AtomicReference<String> seenTool = new AtomicReference<>();

        bridge = new StreamableHttpMcpBridge(true, (attemptId, toolName, args) -> {
            calls.incrementAndGet();
            seenAttempt.set(attemptId);
            seenTool.set(toolName);
            // Handler must not log args/results; return the transient tool response.
            return McpToolCallHandler.McpToolCallResult.ok("[{\"value\":42}]");
        });
        bridge.start();
        assertTrue(bridge.isEnabled());
        assertTrue(bridge.disabledReason().isEmpty());

        InetSocketAddress address = bridge.boundAddress().orElseThrow();
        assertEquals("127.0.0.1", address.getHostString());
        assertTrue(address.getPort() > 0);
        String capability = bridge.capabilityToken().orElseThrow();
        assertTrue(capability.length() >= 64); // 32 bytes hex = 256-bit

        bridge.bindActiveAttempt("attempt-live");

        // initialize
        JsonNode init = postJson(address, capability, rpc("initialize", 1, mapper.createObjectNode()));
        assertEquals("2.0", init.path("jsonrpc").asText());
        assertEquals(1, init.path("id").asInt());
        assertEquals(StreamableHttpMcpBridge.PROTOCOL_VERSION,
                init.path("result").path("protocolVersion").asText());
        assertTrue(init.path("result").path("capabilities").path("tools").isObject());

        // notifications/initialized
        int initializedStatus = postStatus(address, capability,
                notification("notifications/initialized", mapper.createObjectNode()));
        assertEquals(202, initializedStatus);

        // tools/list
        JsonNode list = postJson(address, capability, rpc("tools/list", 2, mapper.createObjectNode()));
        assertTrue(list.path("result").path("tools").isArray());
        assertTrue(list.path("result").path("tools").size() >= 1);
        JsonNode executeSqlDefinition = findTool(list, "execute_sql");
        assertEquals("string", executeSqlDefinition.path("inputSchema").path("properties")
                .path("sql").path("type").asText());
        assertTrue(arrayContains(executeSqlDefinition.path("inputSchema").path("required"), "dataSourceId"));

        JsonNode resources = postJson(address, capability,
                rpc("resources/list", 20, mapper.createObjectNode()));
        assertTrue(resources.path("result").path("resources").isArray());
        assertEquals(0, resources.path("result").path("resources").size());
        JsonNode resourceTemplates = postJson(address, capability,
                rpc("resources/templates/list", 21, mapper.createObjectNode()));
        assertTrue(resourceTemplates.path("result").path("resourceTemplates").isArray());
        assertEquals(0, resourceTemplates.path("result").path("resourceTemplates").size());

        // tools/call
        ObjectNode callParams = mapper.createObjectNode();
        callParams.put("name", "execute_sql");
        callParams.set("arguments", mapper.createObjectNode().put("sql", "SELECT 1"));
        JsonNode call = postJson(address, capability, rpc("tools/call", 3, callParams));
        assertFalse(call.path("result").path("isError").asBoolean());
        assertEquals("[{\"value\":42}]",
                call.path("result").path("content").get(0).path("text").asText());
        assertEquals(1, calls.get());
        assertEquals("attempt-live", seenAttempt.get());
        assertEquals("execute_sql", seenTool.get());
    }

    @Test
    void rejectsMissingCapabilityWrongCapabilityNoLeaseUnknownMethodToolAndOversizedBody()
            throws Exception {
        bridge = new StreamableHttpMcpBridge(true, (attemptId, toolName, args) ->
                McpToolCallHandler.McpToolCallResult.ok("should-not-run"));
        bridge.start();
        InetSocketAddress address = bridge.boundAddress().orElseThrow();
        String capability = bridge.capabilityToken().orElseThrow();

        // Missing capability
        assertEquals(401, postStatus(address, null, rpc("initialize", 1, mapper.createObjectNode())));

        // Wrong capability
        assertEquals(401, postStatus(address, "00".repeat(32),
                rpc("initialize", 1, mapper.createObjectNode())));

        // Unknown method
        JsonNode unknown = postJson(address, capability, rpc("foo/bar", 9, mapper.createObjectNode()));
        assertEquals(-32601, unknown.path("error").path("code").asInt());

        // tools/call without lease
        ObjectNode callParams = mapper.createObjectNode();
        callParams.put("name", "execute_sql");
        callParams.set("arguments", mapper.createObjectNode());
        JsonNode noLease = postJson(address, capability, rpc("tools/call", 10, callParams));
        assertEquals(-32001, noLease.path("error").path("code").asInt());
        assertEquals("NO_ACTIVE_ATTEMPT_LEASE", noLease.path("error").path("message").asText());

        // Unknown tool with lease
        bridge.bindActiveAttempt("attempt-1");
        ObjectNode badTool = mapper.createObjectNode();
        badTool.put("name", "rm_rf");
        badTool.set("arguments", mapper.createObjectNode());
        JsonNode notAllowed = postJson(address, capability, rpc("tools/call", 11, badTool));
        assertEquals(-32602, notAllowed.path("error").path("code").asInt());
        assertEquals("TOOL_NOT_ALLOWLISTED", notAllowed.path("error").path("message").asText());

        // Oversized body
        String huge = "{\"jsonrpc\":\"2.0\",\"id\":12,\"method\":\"initialize\",\"params\":{\"pad\":\""
                + "x".repeat(StreamableHttpMcpBridge.MAX_BODY_BYTES) + "\"}}";
        assertEquals(413, postRawStatus(address, capability, huge));
    }

    @Test
    void featureDisabledDoesNotEnableBridge() {
        bridge = new StreamableHttpMcpBridge(false, (a, t, args) ->
                McpToolCallHandler.McpToolCallResult.ok("x"));
        bridge.start();
        assertFalse(bridge.isEnabled());
        assertEquals("FEATURE_DISABLED", bridge.disabledReason().orElseThrow());
        assertTrue(bridge.boundAddress().isEmpty());
        assertTrue(bridge.capabilityToken().isEmpty());
    }

    @Test
    void readBodyBoundedRejectsOversizeWithoutMaterializingUnboundedArray() throws Exception {
        byte[] oversize = new byte[100];
        InputStream in = new ByteArrayInputStream(oversize);
        assertNull(StreamableHttpMcpBridge.readBodyBounded(in, 64));
    }

    @Test
    void capabilityAcceptedViaAuthorizationBearerHeader() throws Exception {
        bridge = new StreamableHttpMcpBridge(true, (a, t, args) ->
                McpToolCallHandler.McpToolCallResult.ok("ok"));
        bridge.start();
        InetSocketAddress address = bridge.boundAddress().orElseThrow();
        String capability = bridge.capabilityToken().orElseThrow();

        HttpURLConnection connection = open(address);
        connection.setRequestProperty("Authorization", "Bearer " + capability);
        connection.setRequestProperty("Content-Type", "application/json");
        write(connection, mapper.writeValueAsBytes(rpc("initialize", 1, mapper.createObjectNode())));
        assertEquals(200, connection.getResponseCode());
        JsonNode body = mapper.readTree(connection.getInputStream());
        assertNotNull(body.path("result").path("protocolVersion").asText(null));
        connection.disconnect();
    }

    @Test
    void handlerFailureClearsAttemptBindingAndReturnsSafeTerminalError() throws Exception {
        bridge = new StreamableHttpMcpBridge(true, (attemptId, toolName, args) -> {
            throw new IllegalStateException("ledger unavailable with sensitive detail");
        });
        bridge.start();
        InetSocketAddress address = bridge.boundAddress().orElseThrow();
        String capability = bridge.capabilityToken().orElseThrow();
        bridge.bindActiveAttempt("attempt-fail-closed");
        ObjectNode callParams = mapper.createObjectNode();
        callParams.put("name", "execute_sql");
        callParams.set("arguments", mapper.createObjectNode().put("sql", "UPDATE t SET c = 1"));

        JsonNode failed = postJson(address, capability, rpc("tools/call", 21, callParams));

        assertTrue(failed.path("result").path("isError").asBoolean());
        assertEquals("TOOL_OUTCOME_UNKNOWN_LEDGER_UNAVAILABLE",
                failed.path("result").path("content").get(0).path("text").asText());
        assertTrue(bridge.activeAttemptId().isEmpty());
        assertFalse(failed.toString().contains("sensitive detail"));
    }

    /**
     * Pressure path: deliberately call text2sql first, then execute_sql on the same lease.
     * text2sql must stay tool_ok (delegated guidance) and must not clear the attempt so
     * execute_sql can still run.
     */
    @Test
    void text2sqlDelegatedToolOkThenExecuteSqlStillSucceeds() throws Exception {
        SubscriptionMcpToolExecutor realExecutor =
                new SubscriptionMcpToolExecutor(mock(AiToolMcpAdapter.class), mapper);
        List<String> callOrder = new ArrayList<>();

        bridge = new StreamableHttpMcpBridge(true, (attemptId, toolName, args) -> {
            callOrder.add(toolName);
            try {
                if ("text2sql".equals(toolName)) {
                    String text = realExecutor.execute(toolName, args);
                    return McpToolCallHandler.McpToolCallResult.ok(text);
                }
                if ("execute_sql".equals(toolName)) {
                    return McpToolCallHandler.McpToolCallResult.ok(
                            "[{\"day\":\"2026-08-01\",\"cnt\":12}]");
                }
                return McpToolCallHandler.McpToolCallResult.error("UNEXPECTED_TOOL");
            } catch (Exception ex) {
                return McpToolCallHandler.McpToolCallResult.error(ex.getClass().getSimpleName());
            }
        });
        bridge.start();
        InetSocketAddress address = bridge.boundAddress().orElseThrow();
        String capability = bridge.capabilityToken().orElseThrow();
        bridge.bindActiveAttempt("attempt-text2sql-pressure");

        ObjectNode text2sqlParams = mapper.createObjectNode();
        text2sqlParams.put("name", "text2sql");
        ObjectNode text2sqlArgs = mapper.createObjectNode();
        text2sqlArgs.put("question", "daily order volume for geo");
        text2sqlArgs.put("dataSourceId", 1785544596379999L);
        text2sqlArgs.put("databaseName", "geo");
        text2sqlParams.set("arguments", text2sqlArgs);

        JsonNode text2sql = postJson(address, capability, rpc("tools/call", 31, text2sqlParams));
        assertFalse(text2sql.path("result").path("isError").asBoolean(true),
                "text2sql must be tool_ok, body=" + text2sql);
        String text2sqlBody = text2sql.path("result").path("content").get(0).path("text").asText();
        assertTrue(text2sqlBody.startsWith("TEXT2SQL_DELEGATED_TO_SUBSCRIPTION_MODEL:"), text2sqlBody);
        assertTrue(bridge.activeAttemptId().isPresent(), "text2sql must not clear the attempt lease");

        ObjectNode executeParams = mapper.createObjectNode();
        executeParams.put("name", "execute_sql");
        ObjectNode executeArgs = mapper.createObjectNode();
        executeArgs.put("sql", "SELECT 1 AS cnt");
        executeArgs.put("dataSourceId", 1785544596379999L);
        executeArgs.put("databaseName", "geo");
        executeParams.set("arguments", executeArgs);

        JsonNode executeSql = postJson(address, capability, rpc("tools/call", 32, executeParams));
        assertFalse(executeSql.path("result").path("isError").asBoolean(true),
                "execute_sql must succeed after text2sql, body=" + executeSql);
        assertEquals("[{\"day\":\"2026-08-01\",\"cnt\":12}]",
                executeSql.path("result").path("content").get(0).path("text").asText());
        assertEquals(List.of("text2sql", "execute_sql"), callOrder);
    }

    private ObjectNode rpc(String method, long id, ObjectNode params) {
        ObjectNode node = mapper.createObjectNode();
        node.put("jsonrpc", "2.0");
        node.put("id", id);
        node.put("method", method);
        node.set("params", params);
        return node;
    }

    private ObjectNode notification(String method, ObjectNode params) {
        ObjectNode node = mapper.createObjectNode();
        node.put("jsonrpc", "2.0");
        node.put("method", method);
        node.set("params", params);
        return node;
    }

    private static JsonNode findTool(JsonNode response, String name) {
        for (JsonNode tool : response.path("result").path("tools")) {
            if (name.equals(tool.path("name").asText())) {
                return tool;
            }
        }
        throw new IllegalStateException("missing tool " + name);
    }

    private static boolean arrayContains(JsonNode array, String value) {
        for (JsonNode item : array) {
            if (value.equals(item.asText())) {
                return true;
            }
        }
        return false;
    }

    private JsonNode postJson(InetSocketAddress address, String capability, ObjectNode body)
            throws IOException {
        HttpURLConnection connection = open(address);
        if (capability != null) {
            connection.setRequestProperty(StreamableHttpMcpBridge.CAPABILITY_HEADER, capability);
        }
        connection.setRequestProperty("Content-Type", "application/json");
        write(connection, mapper.writeValueAsBytes(body));
        int status = connection.getResponseCode();
        InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
        JsonNode parsed = mapper.readTree(stream);
        connection.disconnect();
        assertEquals(200, status, "expected JSON-RPC over HTTP 200, body=" + parsed);
        return parsed;
    }

    private int postStatus(InetSocketAddress address, String capability, ObjectNode body)
            throws IOException {
        return postRawStatus(address, capability, mapper.writeValueAsString(body));
    }

    private int postRawStatus(InetSocketAddress address, String capability, String body)
            throws IOException {
        HttpURLConnection connection = open(address);
        if (capability != null) {
            connection.setRequestProperty(StreamableHttpMcpBridge.CAPABILITY_HEADER, capability);
        }
        connection.setRequestProperty("Content-Type", "application/json");
        write(connection, body.getBytes(StandardCharsets.UTF_8));
        int status = connection.getResponseCode();
        connection.disconnect();
        return status;
    }

    private static HttpURLConnection open(InetSocketAddress address) throws IOException {
        URI uri = URI.create("http://127.0.0.1:" + address.getPort() + StreamableHttpMcpBridge.MCP_PATH);
        HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
        connection.setConnectTimeout(3_000);
        connection.setReadTimeout(5_000);
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        return connection;
    }

    private static void write(HttpURLConnection connection, byte[] bytes) throws IOException {
        try (OutputStream out = connection.getOutputStream()) {
            out.write(bytes);
        }
    }
}

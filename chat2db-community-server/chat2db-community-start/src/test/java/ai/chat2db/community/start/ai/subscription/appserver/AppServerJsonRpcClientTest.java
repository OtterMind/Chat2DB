package ai.chat2db.community.start.ai.subscription.appserver;

import ai.chat2db.community.start.ai.subscription.appserver.internal.AppServerJsonRpcClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(value = 20, unit = TimeUnit.SECONDS)
class AppServerJsonRpcClientTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private PipeManagedProcess process;
    private AppServerJsonRpcClient client;

    @BeforeEach
    void setUp() throws Exception {
        process = new PipeManagedProcess();
        client = new AppServerJsonRpcClient(process.stdout(), process.stdin(), mapper, 1024 * 64, 5_000L);
    }

    @AfterEach
    void tearDown() {
        if (client != null) {
            client.close();
        }
        if (process != null) {
            process.destroyForcibly();
        }
    }

    @Test
    void correlatesInitializeAndRedactsSecretsFromResults() {
        ObjectNode params = mapper.createObjectNode();
        ObjectNode clientInfo = mapper.createObjectNode();
        clientInfo.put("name", "chat2db_community");
        params.set("clientInfo", clientInfo);

        JsonNode init = client.request("initialize", params);
        assertEquals("codex_app_server/1.0.0", init.path("userAgent").asText());
        assertFalse(init.has("protocolLabel"));

        JsonNode account = client.request("account/read", mapper.createObjectNode());
        assertEquals(SensitivePayloadRedactor.REDACTED, account.path("account").path("accessToken").asText());
        assertEquals("user@example.com", account.path("account").path("email").asText());
    }

    @Test
    void deniesNativeMethodsAtClientBoundary() {
        AppServerException ex = assertThrows(AppServerException.class,
                () -> client.request("command/exec", mapper.createObjectNode()));
        assertEquals(AppServerDisabledReason.METHOD_NOT_ALLOWLISTED, ex.reason());
    }

    @Test
    void dispatchesRedactedNotifications() throws Exception {
        process.fake().setIncludeSecretInLoginCompleted(true);
        CountDownLatch latch = new CountDownLatch(1);
        List<JsonNode> params = new ArrayList<>();
        client.addListener((method, redactedParams) -> {
            if ("account/login/completed".equals(method)) {
                params.add(redactedParams);
                latch.countDown();
            }
        });

        ObjectNode loginParams = mapper.createObjectNode();
        loginParams.put("type", "chatgpt");
        client.request("account/login/start", loginParams);

        assertTrue(latch.await(3, TimeUnit.SECONDS));
        assertEquals(1, params.size());
        assertEquals(SensitivePayloadRedactor.REDACTED, params.get(0).path("refreshToken").asText());
        assertFalse(mapper.writeValueAsString(params.get(0)).contains("refresh-secret-token"));
    }

    @Test
    void failsClosedWhenPeerProcessDies() {
        process.destroyForcibly();
        // Close the client so any blocked pipe I/O unblocks; subsequent request must fail closed.
        client.close();
        AppServerException ex = assertThrows(AppServerException.class,
                () -> client.request("account/read", mapper.createObjectNode()));
        assertEquals(AppServerDisabledReason.SHUTDOWN, ex.reason());
    }

    @Test
    void autoAnswersServerRequestsInsteadOfDroppingThem() throws Exception {
        // Warm the client so the reader is alive.
        client.request("account/read", mapper.createObjectNode());

        ObjectNode params = mapper.createObjectNode();
        params.put("tool", "list_all_datasources");
        params.put("server", "chat2db_subscription");
        process.fake().pushServerRequest(9001L, "item/permissions/requestApproval", params);

        // Wait for auto-response.
        JsonNode response = null;
        for (int i = 0; i < 50; i++) {
            response = process.fake().lastClientResponseToServerRequest();
            if (response != null) {
                break;
            }
            Thread.sleep(20L);
        }
        assertTrue(response != null, "client must answer serverRequest with id+result");
        assertEquals(9001L, response.path("id").asLong());
        assertEquals("approved", response.path("result").path("decision").asText());
    }

    @Test
    void autoDeniesShellApprovalServerRequests() throws Exception {
        client.request("account/read", mapper.createObjectNode());
        process.fake().pushServerRequest(9002L, "item/commandExecution/requestApproval",
                mapper.createObjectNode().put("command", "ls"));

        JsonNode response = null;
        for (int i = 0; i < 50; i++) {
            response = process.fake().lastClientResponseToServerRequest();
            if (response != null && response.path("id").asLong() == 9002L) {
                break;
            }
            Thread.sleep(20L);
        }
        assertTrue(response != null);
        assertEquals("denied", response.path("result").path("decision").asText());
    }

    @Test
    void answersRequestUserInputWithAnswersShapeNotDecision() throws Exception {
        client.request("account/read", mapper.createObjectNode());

        ObjectNode params = mapper.createObjectNode();
        params.put("threadId", "thr_1");
        params.put("turnId", "turn_1");
        params.put("itemId", "call1");
        params.put("isBlocking", true);
        // Bind to Chat2DB MCP so headless auto-answers only apply to scoped tools.
        params.put("server", "chat2db_subscription");
        params.put("tool", "list_all_datasources");
        var questions = params.putArray("questions");
        ObjectNode question = questions.addObject();
        question.put("id", "confirm_path");
        question.put("header", "Confirm");
        question.put("question", "Proceed with list_all_datasources?");
        question.put("isOther", false);
        question.put("isSecret", false);
        var options = question.putArray("options");
        ObjectNode no = options.addObject();
        no.put("label", "No");
        no.put("description", "Cancel the tool call.");
        ObjectNode yes = options.addObject();
        yes.put("label", "Yes (Recommended)");
        yes.put("description", "Continue and call the MCP tool.");

        process.fake().pushServerRequest(9003L, "item/tool/requestUserInput", params);

        JsonNode response = null;
        for (int i = 0; i < 50; i++) {
            response = process.fake().lastClientResponseToServerRequest();
            if (response != null && response.path("id").asLong() == 9003L) {
                break;
            }
            Thread.sleep(20L);
        }
        assertTrue(response != null, "client must answer requestUserInput");
        assertFalse(response.path("result").has("decision"),
                "requestUserInput must not use approval decision shape");
        assertTrue(response.path("result").has("answers"),
                "requestUserInput requires answers field");
        JsonNode answers = response.path("result").path("answers").path("confirm_path").path("answers");
        assertTrue(answers.isArray());
        assertEquals(1, answers.size());
        assertEquals("Yes (Recommended)", answers.get(0).asText());
    }

    @Test
    void acceptsChat2dbMcpElicitationWithActionShape() throws Exception {
        client.request("account/read", mapper.createObjectNode());
        ObjectNode params = mapper.createObjectNode();
        params.put("server", "chat2db_subscription");
        params.put("message", "confirm tool");
        process.fake().pushServerRequest(9004L, "mcpServer/elicitation/request", params);

        JsonNode response = null;
        for (int i = 0; i < 50; i++) {
            response = process.fake().lastClientResponseToServerRequest();
            if (response != null && response.path("id").asLong() == 9004L) {
                break;
            }
            Thread.sleep(20L);
        }
        assertTrue(response != null);
        assertEquals("accept", response.path("result").path("action").asText());
        assertFalse(response.path("result").has("decision"));
    }

    @Test
    void deniesForeignServerPermissionApprovals() throws Exception {
        client.request("account/read", mapper.createObjectNode());
        ObjectNode params = mapper.createObjectNode();
        params.put("tool", "list_all_datasources");
        params.put("server", "evil_mcp");
        process.fake().pushServerRequest(9005L, "item/permissions/requestApproval", params);

        JsonNode response = awaitServerResponse(9005L);
        assertEquals("denied", response.path("result").path("decision").asText());
    }

    @Test
    void deniesBareNetworkPermissionWithoutChat2dbServer() throws Exception {
        client.request("account/read", mapper.createObjectNode());
        ObjectNode params = mapper.createObjectNode();
        params.put("permission", "network");
        params.put("host", "example.com");
        process.fake().pushServerRequest(9006L, "item/permissions/requestApproval", params);

        JsonNode response = awaitServerResponse(9006L);
        assertEquals("denied", response.path("result").path("decision").asText());
    }

    @Test
    void deniesFilesystemShapedPermissionEvenForChat2dbServer() throws Exception {
        client.request("account/read", mapper.createObjectNode());
        ObjectNode params = mapper.createObjectNode();
        params.put("server", "chat2db_subscription");
        params.put("tool", "list_all_datasources");
        params.put("command", "rm -rf /");
        process.fake().pushServerRequest(9007L, "item/permissions/requestApproval", params);

        JsonNode response = awaitServerResponse(9007L);
        assertEquals("denied", response.path("result").path("decision").asText());
    }

    @Test
    void scopedPermissionPredicateAcceptsAllowlistedChat2dbTool() {
        ObjectNode params = mapper.createObjectNode();
        params.put("server", "chat2db_subscription");
        params.put("tool", "execute_sql");
        assertTrue(AppServerJsonRpcClient.isScopedChat2dbMcpPermission(params));
    }

    private JsonNode awaitServerResponse(long id) throws Exception {
        JsonNode response = null;
        for (int i = 0; i < 50; i++) {
            response = process.fake().lastClientResponseToServerRequest();
            if (response != null && response.path("id").asLong() == id) {
                return response;
            }
            Thread.sleep(20L);
        }
        assertTrue(response != null, "client must answer serverRequest id=" + id);
        return response;
    }

}

package ai.chat2db.community.web.api.controller;

import ai.chat2db.community.domain.api.model.agent.AgentDataScope;
import ai.chat2db.community.domain.api.model.agent.AgentRuntimeTaskScope;
import ai.chat2db.community.domain.api.model.request.ai.AiExecuteSqlRequest;
import ai.chat2db.community.domain.api.service.agent.IAgentRuntimeDispatchService;
import ai.chat2db.community.domain.api.service.ai.IAiToolService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentRuntimeMcpControllerTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void implementsMcpInitializeToolListAndNotificationProtocol() {
        AtomicReference<String> authorizedRun = new AtomicReference<>();
        AtomicReference<String> authorizedToken = new AtomicReference<>();
        IAgentRuntimeDispatchService dispatch = dispatch((runId, token) -> {
            authorizedRun.set(runId);
            authorizedToken.set(token);
            return scope();
        });
        AgentRuntimeMcpController controller = new AgentRuntimeMcpController(
                dispatch, unsupportedTools(), mapper);

        ResponseEntity<JsonNode> initialized = controller.handle(
                "run-1", "Bearer task-secret", request(1, "initialize",
                        mapper.createObjectNode().put("protocolVersion", "2025-06-18")));
        ResponseEntity<JsonNode> listed = controller.handle(
                "run-1", "Bearer task-secret", request(2, "tools/list", mapper.createObjectNode()));
        ObjectNode notification = mapper.createObjectNode();
        notification.put("jsonrpc", "2.0");
        notification.put("method", "notifications/initialized");
        ResponseEntity<JsonNode> accepted = controller.handle(
                "run-1", "Bearer task-secret", notification);

        assertEquals(HttpStatus.OK, initialized.getStatusCode());
        assertEquals("2025-06-18",
                initialized.getBody().path("result").path("protocolVersion").asText());
        assertTrue(initialized.getBody().path("result").path("capabilities").has("tools"));
        assertEquals(6, listed.getBody().path("result").path("tools").size());
        assertEquals("execute_sql",
                listed.getBody().path("result").path("tools").get(5).path("name").asText());
        assertEquals(HttpStatus.ACCEPTED, accepted.getStatusCode());
        assertEquals("run-1", authorizedRun.get());
        assertEquals("task-secret", authorizedToken.get());
    }

    @Test
    void injectsAuthorizedRunScopeAndNeverAcceptsCallerToolContext() {
        AgentDataScope dataScope = new AgentDataScope();
        dataScope.setDataSourceId(42L);
        IAgentRuntimeDispatchService dispatch = dispatch((runId, token) -> {
            AgentRuntimeTaskScope scope = scope();
            scope.setDataScopes(List.of(dataScope));
            return scope;
        });
        AtomicReference<AiExecuteSqlRequest> captured = new AtomicReference<>();
        IAiToolService tools = proxy(IAiToolService.class, (proxy, method, args) -> {
            if ("executeSql".equals(method.getName())) {
                captured.set((AiExecuteSqlRequest) args[0]);
                return "query result";
            }
            throw new UnsupportedOperationException(method.getName());
        });
        AgentRuntimeMcpController controller = new AgentRuntimeMcpController(dispatch, tools, mapper);
        ObjectNode arguments = mapper.createObjectNode();
        arguments.put("sql", "select 1");
        arguments.put("dataSourceId", 42L);
        arguments.put("databaseName", "analytics");
        arguments.putObject("aiToolContextRequest").put("agentRunId", "spoofed-run");
        ObjectNode params = mapper.createObjectNode();
        params.put("name", "execute_sql");
        params.set("arguments", arguments);

        JsonNode body = controller.handle("run-1", "Bearer task-secret",
                request(3, "tools/call", params)).getBody();

        assertFalse(body.path("result").path("isError").asBoolean());
        assertEquals("query result",
                body.path("result").path("content").get(0).path("text").asText());
        assertEquals("run-1", captured.get().getAiToolContextRequest().getAgentRunId());
        assertEquals(List.of(dataScope), captured.get().getAiToolContextRequest().getAgentDataScopes());
        assertFalse(captured.get().getAiToolContextRequest().getWaitForApprovalDecision());
        assertEquals(42L, captured.get().getDataSourceId());
        assertEquals("analytics", captured.get().getDatabaseName());
    }

    @Test
    void rejectsInvalidTaskTokenAndReturnsToolErrorsAsMcpResults() {
        IAgentRuntimeDispatchService rejecting = dispatch((runId, token) -> {
            throw new SecurityException("invalid token");
        });
        AgentRuntimeMcpController rejectedController = new AgentRuntimeMcpController(
                rejecting, unsupportedTools(), mapper);

        ResponseEntity<JsonNode> rejected = rejectedController.handle(
                "run-1", "Bearer wrong", request(4, "tools/list", mapper.createObjectNode()));

        assertEquals(HttpStatus.UNAUTHORIZED, rejected.getStatusCode());
        assertEquals(-32001, rejected.getBody().path("error").path("code").asInt());

        AgentRuntimeMcpController controller = new AgentRuntimeMcpController(
                dispatch((runId, token) -> scope()), unsupportedTools(), mapper);
        ObjectNode params = mapper.createObjectNode();
        params.put("name", "unknown_tool");
        params.set("arguments", mapper.createObjectNode());
        JsonNode unknown = controller.handle("run-1", "Bearer task-secret",
                request(5, "tools/call", params)).getBody();
        assertTrue(unknown.path("result").path("isError").asBoolean());
    }

    @Test
    void rejectsFractionalDatasourceIdsAndOutOfRangePageSizes() {
        AgentRuntimeMcpController controller = new AgentRuntimeMcpController(
                dispatch((runId, token) -> scope()), unsupportedTools(), mapper);
        ObjectNode listParams = mapper.createObjectNode();
        listParams.put("name", "list_all_databases");
        listParams.putObject("arguments").put("dataSourceId", 1.5D);

        JsonNode fractionalId = controller.handle("run-1", "Bearer task-secret",
                request(6, "tools/call", listParams)).getBody();

        assertTrue(fractionalId.path("result").path("isError").asBoolean());
        assertTrue(fractionalId.path("result").path("content").get(0).path("text")
                .asText().contains("positive integer"));

        ObjectNode executeParams = mapper.createObjectNode();
        executeParams.put("name", "execute_sql");
        ObjectNode arguments = executeParams.putObject("arguments");
        arguments.put("sql", "select 1");
        arguments.put("pageSize", 501);
        arguments.put("dataSourceId", 42L);
        arguments.put("databaseName", "analytics");

        JsonNode oversizedPage = controller.handle("run-1", "Bearer task-secret",
                request(7, "tools/call", executeParams)).getBody();

        assertTrue(oversizedPage.path("result").path("isError").asBoolean());
        assertTrue(oversizedPage.path("result").path("content").get(0).path("text")
                .asText().contains("between 1 and 500"));
    }

    private ObjectNode request(int id, String method, JsonNode params) {
        ObjectNode request = mapper.createObjectNode();
        request.put("jsonrpc", "2.0");
        request.put("id", id);
        request.put("method", method);
        request.set("params", params);
        return request;
    }

    private AgentRuntimeTaskScope scope() {
        AgentRuntimeTaskScope scope = new AgentRuntimeTaskScope();
        scope.setRunId("run-1");
        scope.setTaskId("task-1");
        scope.setAgentId("agent-1");
        scope.setDataScopes(List.of());
        return scope;
    }

    private IAgentRuntimeDispatchService dispatch(TaskAuthorizer authorizer) {
        return proxy(IAgentRuntimeDispatchService.class, (proxy, method, args) -> {
            if ("authorizeTaskToken".equals(method.getName())) {
                return authorizer.authorize((String) args[0], (String) args[1]);
            }
            throw new UnsupportedOperationException(method.getName());
        });
    }

    private IAiToolService unsupportedTools() {
        return proxy(IAiToolService.class,
                (proxy, method, args) -> { throw new UnsupportedOperationException(method.getName()); });
    }

    @SuppressWarnings("unchecked")
    private <T> T proxy(Class<T> type, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{type}, handler);
    }

    @FunctionalInterface
    private interface TaskAuthorizer {
        AgentRuntimeTaskScope authorize(String runId, String token);
    }
}

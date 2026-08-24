package ai.chat2db.community.web.api.controller;

import ai.chat2db.community.domain.api.model.agent.AgentConnectorContext;
import ai.chat2db.community.domain.api.model.agent.AgentRuntimeTaskScope;
import ai.chat2db.community.domain.api.service.agent.IAgentConnectorService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class AgentConnectorControllerTest {

    @Test
    void returnsAuthorizedConnectorContextUsingBearerToken() {
        AtomicReference<String> token = new AtomicReference<>();
        AgentConnectorContext expected = new AgentConnectorContext();
        expected.setSessionId("session-1");
        expected.setAgentId("agent-1");
        expected.setAgentName("Finance");
        IAgentConnectorService service = proxy((proxy, method, args) -> {
            if ("context".equals(method.getName())) {
                token.set((String) args[1]);
                return expected;
            }
            throw new UnsupportedOperationException(method.getName());
        });
        AgentConnectorController controller = new AgentConnectorController(service, null, null, null);

        var response = controller.context("session-1", "Bearer access-secret");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(expected, response.getBody());
        assertEquals("access-secret", token.get());
    }

    @Test
    void rejectsMissingOrInvalidConnectorAccessToken() {
        IAgentConnectorService service = proxy((proxy, method, args) -> {
            throw new SecurityException("invalid token");
        });
        AgentConnectorController controller = new AgentConnectorController(service, null, null, null);

        assertEquals(HttpStatus.UNAUTHORIZED, controller.context("session-1", null).getStatusCode());
        assertEquals(HttpStatus.UNAUTHORIZED, controller.context("session-1", "Basic secret").getStatusCode());
    }

    @Test
    void correlatesToolCallAndReplaysStoredIdempotentResult() throws Exception {
        AtomicReference<Object[]> arguments = new AtomicReference<>();
        IAgentConnectorService service = proxy((proxy, method, args) -> {
            if ("authorizeToolCall".equals(method.getName())) {
                arguments.set(args);
                AgentRuntimeTaskScope scope = new AgentRuntimeTaskScope();
                scope.setConnectorReplayResultJson("{\"content\":[{\"type\":\"text\",\"text\":\"cached\"}],\"isError\":false}");
                return scope;
            }
            throw new UnsupportedOperationException(method.getName());
        });
        AgentRuntimeMcpController mcp = new AgentRuntimeMcpController(null, null, new ObjectMapper());
        AgentConnectorController controller = new AgentConnectorController(service, null, null, mcp);
        var request = new ObjectMapper().readTree("""
                {"jsonrpc":"2.0","id":27,"method":"tools/call","params":{"name":"execute_sql","arguments":{"sql":"select 1"}}}
                """);

        var response = controller.mcp("connector-1", "Bearer access", "dsh-session-1", "call-1", request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(27, response.getBody().path("id").asInt());
        assertEquals("cached", response.getBody().path("result").path("content").get(0).path("text").asText());
        assertNotNull(arguments.get());
        assertEquals("dsh-session-1", arguments.get()[2]);
        assertEquals("call-1", arguments.get()[3]);
    }

    @Test
    void returnsJsonRpcErrorBodyWhenConnectorAccessTokenIsRejected() throws Exception {
        IAgentConnectorService service = proxy((proxy, method, args) -> {
            throw new SecurityException("expired access token");
        });
        AgentRuntimeMcpController mcp = new AgentRuntimeMcpController(null, null, new ObjectMapper());
        AgentConnectorController controller = new AgentConnectorController(service, null, null, mcp);
        var request = new ObjectMapper().readTree("""
                {"jsonrpc":"2.0","id":31,"method":"tools/call","params":{"name":"list_all_datasources","arguments":{}}}
                """);

        var response = controller.mcp("connector-1", "Bearer expired", "dsh-session-1", "call-1", request);

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(31, response.getBody().path("id").asInt());
        assertEquals(-32001, response.getBody().path("error").path("code").asInt());
    }

    private static IAgentConnectorService proxy(java.lang.reflect.InvocationHandler handler) {
        return (IAgentConnectorService) Proxy.newProxyInstance(IAgentConnectorService.class.getClassLoader(),
                new Class<?>[]{IAgentConnectorService.class}, handler);
    }
}

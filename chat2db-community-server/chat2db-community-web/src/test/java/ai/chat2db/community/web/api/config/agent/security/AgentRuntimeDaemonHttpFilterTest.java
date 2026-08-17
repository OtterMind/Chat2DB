package ai.chat2db.community.web.api.config.agent.security;

import ai.chat2db.community.web.api.util.AgentRuntimeDaemonUtils;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentRuntimeDaemonHttpFilterTest {

    @AfterEach
    void clearToken() {
        System.clearProperty(AgentRuntimeDaemonUtils.TOKEN_PROPERTY);
    }

    @Test
    void protectsOnlyDaemonEndpointsWithBearerToken() throws Exception {
        System.setProperty(AgentRuntimeDaemonUtils.TOKEN_PROPERTY, "runtime-secret");
        String expectedToken = AgentRuntimeDaemonUtils.runtimeToken();
        AgentRuntimeDaemonHttpFilter filter = new AgentRuntimeDaemonHttpFilter();
        assertTrue(filter.shouldNotFilter(request("/api/agent/runtime-profiles", null)));
        assertTrue(filter.shouldNotFilter(request("/api/agent/runtime/mcp/runs/run-1", null)));
        assertFalse(filter.shouldNotFilter(request("/api/agent/runtime/daemon/instances/register", null)));

        AtomicBoolean rejectedChain = new AtomicBoolean();
        ResponseCapture rejected = new ResponseCapture();
        filter.doFilterInternal(
                request("/api/agent/runtime/daemon/instances/register", "Bearer wrong"),
                rejected.response(),
                (request, response) -> rejectedChain.set(true));
        assertFalse(rejectedChain.get());
        assertEquals(HttpServletResponse.SC_UNAUTHORIZED, rejected.status.get());
        assertTrue(rejected.body.toString().contains("agent_runtime_unauthorized"));

        AtomicBoolean acceptedChain = new AtomicBoolean();
        ResponseCapture accepted = new ResponseCapture();
        filter.doFilterInternal(
                request("/api/agent/runtime/daemon/instances/register", "Bearer " + expectedToken),
                accepted.response(),
                (request, response) -> acceptedChain.set(true));
        assertTrue(acceptedChain.get());
    }

    private HttpServletRequest request(String uri, String authorization) {
        return (HttpServletRequest) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{HttpServletRequest.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getRequestURI" -> uri;
                    case "getHeader" -> "Authorization".equals(args[0]) ? authorization : null;
                    default -> defaultValue(method.getReturnType());
                });
    }

    private Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0F;
        if (type == double.class) return 0D;
        if (type == char.class) return '\0';
        return null;
    }

    private class ResponseCapture {
        private final AtomicInteger status = new AtomicInteger(HttpServletResponse.SC_OK);
        private final StringWriter body = new StringWriter();

        private HttpServletResponse response() {
            return (HttpServletResponse) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{HttpServletResponse.class}, (proxy, method, args) -> switch (method.getName()) {
                        case "setStatus" -> {
                            status.set((Integer) args[0]);
                            yield null;
                        }
                        case "setContentType" -> null;
                        case "getWriter" -> new PrintWriter(body);
                        default -> defaultValue(method.getReturnType());
                    });
        }
    }
}

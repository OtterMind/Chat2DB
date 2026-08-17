package ai.chat2db.community.web.api.config.agent.security;

import ai.chat2db.community.web.api.util.AgentRuntimeDaemonUtils;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.commons.lang3.StringUtils;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 2)
public class AgentRuntimeDaemonHttpFilter extends OncePerRequestFilter {

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path == null || !path.startsWith(AgentRuntimeDaemonUtils.API_PREFIX);
    }

    @Override
    protected boolean shouldNotFilterErrorDispatch() {
        return true;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String expectedToken = AgentRuntimeDaemonUtils.runtimeToken();
        if (StringUtils.isBlank(expectedToken)) {
            writeJson(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                    "{\"success\":false,\"error\":{\"code\":\"agent_runtime_token_missing\","
                            + "\"message\":\"Agent runtime daemon token is not configured.\","
                            + "\"details\":{}},\"data\":null,\"requestId\":null}");
            return;
        }
        String actualToken = bearerToken(request.getHeader(HttpHeaders.AUTHORIZATION));
        if (!secureEquals(expectedToken, actualToken)) {
            writeJson(response, HttpServletResponse.SC_UNAUTHORIZED,
                    "{\"success\":false,\"error\":{\"code\":\"agent_runtime_unauthorized\","
                            + "\"message\":\"Agent runtime access requires a valid bearer token.\","
                            + "\"details\":{}},\"data\":null,\"requestId\":null}");
            return;
        }
        filterChain.doFilter(request, response);
    }

    private String bearerToken(String authorization) {
        if (StringUtils.isBlank(authorization) || !authorization.startsWith("Bearer ")) {
            return null;
        }
        return StringUtils.trimToNull(authorization.substring("Bearer ".length()));
    }

    private boolean secureEquals(String expected, String actual) {
        if (actual == null) {
            return false;
        }
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
    }

    private void writeJson(HttpServletResponse response, int status, String body) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(body);
    }
}

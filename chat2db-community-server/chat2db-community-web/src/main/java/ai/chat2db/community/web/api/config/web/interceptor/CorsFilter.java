package ai.chat2db.community.web.api.config.web.interceptor;

import java.io.IOException;
import java.util.Set;

import ai.chat2db.community.tools.util.ConfigUtils;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;


@Component
public class CorsFilter implements Filter {

    private static final Set<String> COMMUNITY_ALLOWED_ORIGINS = Set.of(
            "http://127.0.0.1:8888",
            "http://localhost:8888",
            "http://127.0.0.1:8889",
            "http://127.0.0.1:10825",
            "http://localhost:10825"
    );

    private final boolean devProfileActive;

    public CorsFilter(Environment environment) {
        this.devProfileActive = environment.matchesProfiles("dev");
    }

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
        throws IOException, ServletException {
        HttpServletResponse response = (HttpServletResponse)res;
        HttpServletRequest request = (HttpServletRequest)req;
        String origin = request.getHeader(HttpHeaders.ORIGIN);

        if (ConfigUtils.isCommunity() && !allowCommunityOrigin(origin, devProfileActive)) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN);
            return;
        }
        setCorsHeaders(response, origin);
        response.setHeader("Access-Control-Allow-Methods", "POST, GET, PUT, OPTIONS, DELETE");
        response.setHeader("Access-Control-Max-Age", "3600");
        response.setHeader("Access-Control-Allow-Headers", "Content-Type, DBHUB, uid, Time-Zone");
        chain.doFilter(req, res);
    }

    static boolean allowCommunityOrigin(String origin, boolean devProfileActive) {
        return devProfileActive || origin == null || origin.isBlank() || COMMUNITY_ALLOWED_ORIGINS.contains(origin);
    }

    private static void setCorsHeaders(HttpServletResponse response, String origin) {
        response.setHeader("Access-Control-Allow-Origin", origin);
        response.setHeader("Access-Control-Allow-Credentials", "true");
        response.setHeader(HttpHeaders.VARY, HttpHeaders.ORIGIN);
    }

}

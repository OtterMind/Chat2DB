package ai.chat2db.community.web.api.config.mcp.security;

import ai.chat2db.community.tools.util.SystemSettingsUtil;
import ai.chat2db.community.tools.util.ConfigUtils;
import ai.chat2db.community.tools.annotation.NotCliRuntime;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
@NotCliRuntime
@Order(Ordered.HIGHEST_PRECEDENCE)
public class DesktopMcpHttpFilter extends OncePerRequestFilter {

    @Override
    protected boolean shouldNotFilterErrorDispatch() {
        return true;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (!isDesktopGuiMcpMode()) {
            filterChain.doFilter(request, response);
            return;
        }
        String path = request.getRequestURI();
        if (allowedPath(path)) {
            filterChain.doFilter(request, response);
            return;
        }
        response.setStatus(HttpServletResponse.SC_NOT_FOUND);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("""
                {"success":false,"errorCode":"desktop.mcp.http.locked","errorMessage":"Desktop GUI mode only exposes MCP endpoints over HTTP."}
                """);
    }

    private boolean isDesktopGuiMcpMode() {
        return ConfigUtils.isDesktop() && ConfigUtils.isShowGUI() && SystemSettingsUtil.isMcpEnabled();
    }

    static boolean allowedPath(String path) {
        return path != null && (path.equals("/mcp") || path.startsWith("/mcp/")
                || path.startsWith("/api/agent/runtime/daemon/")
                || path.startsWith("/api/agent/runtime/mcp/runs/")
                || path.startsWith("/api/agent/connectors/")
                || path.startsWith("/api/agent/gateway/channels/"));
    }
}

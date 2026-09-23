package ai.chat2db.community.web.api.controller;

import ai.chat2db.community.web.api.config.console.DesktopBridgeRequestContext;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * State-changing agent capabilities (shell enablement, working directory, approvals) are local operations.
 * Without this gate any caller that can reach the port could grant itself shell access and approve it.
 */
public final class AgentLocalRequestGuard {
    private AgentLocalRequestGuard() { }

    public static void requireLocal() {
        if (DesktopBridgeRequestContext.isActive()) return;
        String remote = RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes servlet
                ? servlet.getRequest().getRemoteAddr() : null;
        if (!("127.0.0.1".equals(remote) || "::1".equals(remote) || "0:0:0:0:0:0:0:1".equals(remote))) {
            throw new SecurityException("Agent settings are available only on the local computer");
        }
    }
}

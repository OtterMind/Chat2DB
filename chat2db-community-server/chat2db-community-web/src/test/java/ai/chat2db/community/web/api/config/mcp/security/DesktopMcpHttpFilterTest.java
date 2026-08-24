package ai.chat2db.community.web.api.config.mcp.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DesktopMcpHttpFilterTest {

    @Test
    void desktopMcpModeAllowsOnlyMcpAndIndependentlyAuthenticatedRuntimePaths() {
        assertTrue(DesktopMcpHttpFilter.allowedPath("/mcp"));
        assertTrue(DesktopMcpHttpFilter.allowedPath("/api/agent/runtime/daemon/instances/register"));
        assertTrue(DesktopMcpHttpFilter.allowedPath("/api/agent/runtime/mcp/runs/run-1"));
        assertTrue(DesktopMcpHttpFilter.allowedPath("/api/agent/connectors/pairings"));
        assertTrue(DesktopMcpHttpFilter.allowedPath("/api/agent/gateway/channels/channel-1/inbound"));
        assertFalse(DesktopMcpHttpFilter.allowedPath("/api/agent/runtime-profiles"));
        assertFalse(DesktopMcpHttpFilter.allowedPath("/api/connection/list"));
        assertFalse(DesktopMcpHttpFilter.allowedPath(null));
    }
}

package ai.chat2db.community.web.api.adapter.agent;

import ai.chat2db.community.domain.api.enums.agent.McpToolPolicy;
import ai.chat2db.community.domain.api.model.agent.mcp.McpServerConfig;
import ai.chat2db.community.domain.api.model.agent.mcp.McpServerState;
import ai.chat2db.community.domain.api.model.agent.mcp.McpToolDescriptor;
import ai.chat2db.community.domain.api.service.agent.IMcpServerService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentMcpToolRegistryTest {

    private final FakeServers servers = new FakeServers();
    private final AgentMcpToolRegistry registry = new AgentMcpToolRegistry(servers);

    @Test
    void managementToolsStayInactiveUntilASearchActivatesThem() {
        assertEquals(6, registry.definitions().size());
        for (var tool : registry.definitions()) {
            assertEquals(AgentMcpToolRegistry.GROUP, tool.group());
            assertFalse(tool.defaultActive(), tool.name() + " must stay inactive until tool_search asks for it");
            assertEquals("object", ((Map<?, ?>) tool.parameters()).get("type"));
        }
        assertTrue(registry.contains(AgentMcpToolRegistry.ADD));
        assertFalse(registry.contains("mcp_unknown_tool"));
        for (var tool : registry.definitions()) {
            Map<?, ?> schema = (Map<?, ?>) tool.parameters();
            Map<?, ?> properties = (Map<?, ?>) schema.get("properties");
            assertFalse(properties.containsKey("properties"), tool.name() + " must not nest a second schema");
            for (var entry : properties.entrySet()) {
                assertTrue(entry.getValue() instanceof Map, tool.name() + "." + entry.getKey()
                        + " must be a schema object, not a bare value");
            }
            assertTrue(schema.get("additionalProperties") instanceof Boolean, tool.name());
        }
    }

    @Test
    void listsConfiguredServersWithTheRealConfigPath() {
        var response = registry.execute(AgentMcpToolRegistry.LIST, Map.of());

        assertTrue(response.ok());
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) response.data();
        assertEquals("/fixture/settings/mcp.json", data.get("configPath"));
        assertEquals(1, ((List<?>) data.get("servers")).size());
        assertEquals(0, servers.writes);
    }

    @Test
    void addingAServerReportsAConnectionFailureWithoutLosingTheConfiguration() {
        servers.failRefresh = true;

        var response = registry.execute(AgentMcpToolRegistry.ADD, Map.of(
                "name", "filesystem", "transport", "stdio", "command", "npx",
                "args", List.of("-y", "server-filesystem"), "environment_keys", List.of("API_KEY"),
                "secrets", Map.of("API_KEY", "token")));

        assertTrue(response.ok(), "the configuration was written, so the call itself succeeded");
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) response.data();
        assertEquals(false, data.get("connected"));
        assertTrue(String.valueOf(data.get("error")).contains("not installed"));
        assertTrue(String.valueOf(data.get("hint")).contains("OAuth"));
        assertEquals(1, servers.writes);
    }

    @Test
    void testingAServerReportsTheToolListAndExplainableFailures() {
        var ok = registry.execute(AgentMcpToolRegistry.TEST, Map.of("name", "filesystem"));
        assertTrue(ok.ok());
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) ok.data();
        assertEquals(1, ((List<?>) data.get("tools")).size());

        servers.failRefresh = true;
        var failed = registry.execute(AgentMcpToolRegistry.TEST, Map.of("name", "filesystem"));
        assertFalse(failed.ok());
        assertEquals("MCP_CONNECTION_FAILED", failed.error().code());
        assertTrue(failed.error().message().contains("not installed"));
    }

    @Test
    void rejectsUnknownFieldsAndUnknownPolicies() {
        assertFalse(registry.execute(AgentMcpToolRegistry.ADD, Map.of("name", "x", "command", "npx", "token", "s")).ok());
        assertFalse(registry.execute(AgentMcpToolRegistry.SET_POLICY, Map.of("name", "filesystem", "policy", "MAYBE")).ok());
        assertFalse(registry.execute(AgentMcpToolRegistry.REMOVE, Map.of()).ok());
    }

    private static final class FakeServers implements IMcpServerService {
        int writes;
        boolean failRefresh;
        McpServerConfig config = new McpServerConfig("filesystem", ai.chat2db.community.domain.api.enums.agent.McpTransport.STDIO,
                "npx", List.of("-y", "server-filesystem"), null, List.of(), List.of("API_KEY"),
                Map.of("API_KEY", "token"), true, McpToolPolicy.ASK, List.of(), null,
                List.of(new McpToolDescriptor("read_file", "Read a file", Map.of("type", "object"), true, false)), "now");

        @Override public String configPath() { return "/fixture/settings/mcp.json"; }
        @Override public List<McpServerState> list() { return List.of(McpServerState.from(config)); }
        @Override public McpServerConfig require(String name) { return config; }
        @Override public McpServerState add(McpServerRegistration registration) { writes++; return McpServerState.from(config); }
        @Override public McpServerState update(String name, McpServerRegistration registration) { writes++; return McpServerState.from(config); }
        @Override public void remove(String name) { writes++; }
        @Override public McpServerState setEnabled(String name, boolean enabled) { writes++; return McpServerState.from(config); }
        @Override public McpServerState setPolicy(String name, McpToolPolicy policy, List<String> allowedTools) {
            writes++;
            return McpServerState.from(config);
        }
        @Override public McpServerState refreshTools(String name) {
            if (failRefresh) throw new IllegalStateException("the command is not installed");
            return McpServerState.from(config);
        }
        @Override public List<McpServerConfig> enabledServers() { return List.of(config); }
        @Override public void rememberTool(String name, String toolName) { writes++; }
        @Override public void rememberServer(String name) { writes++; }
        @Override public boolean isToolAllowed(McpServerConfig config, String toolName) { return false; }
        @Override public String commandHash(McpServerConfig config) { return "hash"; }
        @Override public void approveCommand(String name, String commandHash) { writes++; }
        @Override public boolean isCommandApproved(McpServerConfig config, String commandHash) { return false; }
    }
}

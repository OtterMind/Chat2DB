package ai.chat2db.community.domain.core.impl.agent;

import ai.chat2db.community.domain.api.enums.agent.McpToolPolicy;
import ai.chat2db.community.domain.api.enums.agent.McpTransport;
import ai.chat2db.community.domain.api.model.agent.mcp.McpServerConfig;
import ai.chat2db.community.domain.api.model.agent.mcp.McpToolDescriptor;
import ai.chat2db.community.domain.api.service.agent.IMcpServerService;
import ai.chat2db.community.domain.api.service.agent.IMcpServerService.McpServerRegistration;
import ai.chat2db.community.domain.api.service.agent.IMcpServerStorage;
import ai.chat2db.community.domain.api.service.agent.IMcpToolDiscovery;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpServerServiceImplTest {

    private final MemoryStorage storage = new MemoryStorage();
    private final MemoryDiscovery discovery = new MemoryDiscovery();
    private final McpServerServiceImpl service = new McpServerServiceImpl(storage, provider(discovery));

    @Test
    void addsStdioServersWithDerivedNamesAndDiscoversTheirTools() {
        var state = service.add(new McpServerRegistration(null, "stdio", "npx",
                List.of("-y", "@modelcontextprotocol/server-filesystem"), null, null, List.of("API_KEY"),
                Map.of("API_KEY", "token"), null, null));

        assertEquals("npx", state.name());
        assertEquals(McpTransport.STDIO, state.transport());
        assertTrue(state.enabled());
        assertEquals(McpToolPolicy.ASK, state.policy());
        assertEquals(List.of("API_KEY"), state.environmentKeys());
        assertFalse(state.commandApproved());
        assertEquals(1, storage.servers.size());
        assertTrue(service.require("npx").tools().isEmpty(), "a new server has no cached tools yet");

        var discovered = service.refreshTools("npx");
        assertEquals(1, discovered.toolCount());
        assertEquals("npx", discovery.lastServer);
    }

    @Test
    void rejectsNamesAndEndpointsThatWouldEscapeThePolicy() {
        assertThrows(IllegalArgumentException.class, () -> service.add(new McpServerRegistration("Bad Name", "stdio",
                "npx", List.of(), null, null, null, null, null, null)));
        assertThrows(IllegalArgumentException.class, () -> service.add(new McpServerRegistration("remote", "http",
                null, null, "http://example.com/mcp", null, null, null, null, null)));
        assertThrows(IllegalArgumentException.class, () -> service.add(new McpServerRegistration("metadata", "http",
                null, null, "http://169.254.169.254/latest/meta-data", null, null, null, null, null)));
        var added = service.add(new McpServerRegistration("local", "http", null, null,
                "http://127.0.0.1:3000/mcp", List.of("Authorization"), null, Map.of("Authorization", "Bearer x"),
                null, null));
        assertEquals(McpTransport.HTTP, added.transport());
        assertEquals(List.of("Authorization"), added.headerNames());
    }

    @Test
    void keepsApprovalsUntilWhatRunsOrWhereItConnectsChanges() {
        McpServerConfig config = service.require(service.add(new McpServerRegistration("filesystem", "stdio",
                "npx", List.of("server-filesystem"), null, null, null, null, null, null)).name())
                .withTools(List.of(new McpToolDescriptor("read_file", "Read", Map.of(), true, false)), "now");
        storage.replace(config);
        String hash = service.commandHash(config);

        assertFalse(service.isCommandApproved(config, hash));
        service.approveCommand("filesystem", hash);
        assertTrue(service.isCommandApproved(service.require("filesystem"), hash));

        service.rememberTool("filesystem", "read_file");
        service.rememberServer("filesystem");
        assertEquals(McpToolPolicy.ALLOW, service.require("filesystem").policy());
        assertTrue(service.isToolAllowed(service.require("filesystem"), "anything"));

        service.setPolicy("filesystem", McpToolPolicy.ASK, List.of("read_file"));
        assertTrue(service.isToolAllowed(service.require("filesystem"), "read_file"));
        assertFalse(service.isToolAllowed(service.require("filesystem"), "write_file"));

        var updated = service.update("filesystem", new McpServerRegistration("filesystem", "stdio", "npx",
                List.of("other-server"), null, null, null, null, null, null));
        assertEquals(List.of("read_file"), service.require(updated.name()).allowedTools(),
                "remembered tools survive an unrelated edit");
        assertNull(service.require(updated.name()).approvedCommandHash(),
                "a changed launch line must be approved again");
        assertEquals(1, service.require(updated.name()).tools().size(),
                "a changed server re-discovers its tools right away");
    }

    @Test
    void disablesAndRemovesServersWithoutTouchingTheOthers() {
        service.add(new McpServerRegistration("alpha", "stdio", "alpha-cmd", List.of(), null, null, null, null, null, null));
        service.add(new McpServerRegistration("beta", "stdio", "beta-cmd", List.of(), null, null, null, null, null, null));

        service.setEnabled("alpha", false);
        assertEquals(List.of("beta"), service.enabledServers().stream().map(McpServerConfig::name).toList());

        service.remove("beta");
        assertEquals(List.of("alpha"), service.list().stream().map(state -> state.name()).toList());
        assertThrows(IllegalArgumentException.class, () -> service.require("beta"));
        assertTrue(discovery.closed.contains("beta"));
    }

    @Test
    void reportsAMissingRuntimeWhenDiscoveryIsUnavailable() {
        McpServerServiceImpl withoutClient = new McpServerServiceImpl(storage, provider(null));
        withoutClient.add(new McpServerRegistration("alpha", "stdio", "alpha-cmd", List.of(), null, null, null, null, null, null));

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> withoutClient.refreshTools("alpha"));
        assertTrue(error.getMessage().contains("MCP support"));
    }

    private static ObjectProvider<IMcpToolDiscovery> provider(IMcpToolDiscovery client) {
        return new ObjectProvider<>() {
            @Override public IMcpToolDiscovery getObject() { return require(); }
            @Override public IMcpToolDiscovery getObject(Object... args) { return require(); }
            @Override public IMcpToolDiscovery getIfAvailable() { return client; }
            @Override public IMcpToolDiscovery getIfUnique() { return client; }
            private IMcpToolDiscovery require() {
                if (client == null) throw new IllegalStateException("MCP support is not available in this runtime");
                return client;
            }
        };
    }

    private static final class MemoryStorage implements IMcpServerStorage {
        List<McpServerConfig> servers = new ArrayList<>();

        @Override public String path() { return "/tmp/mcp.json"; }
        @Override public List<McpServerConfig> load() { return List.copyOf(servers); }
        @Override public void save(List<McpServerConfig> values) { servers = new ArrayList<>(values); }

        void replace(McpServerConfig config) {
            servers.replaceAll(existing -> existing.name().equals(config.name()) ? config : existing);
        }
    }

    private static final class MemoryDiscovery implements IMcpToolDiscovery {
        final List<String> closed = new ArrayList<>();
        String lastServer;

        @Override public List<McpToolDescriptor> discover(McpServerConfig config) {
            lastServer = config.name();
            return List.of(new McpToolDescriptor("read_file", "Read a file", Map.of("type", "object"), true, false));
        }

        @Override public McpToolCallResult call(McpServerConfig config, String toolName, Map<String, Object> arguments) {
            return McpToolCallResult.success("ok");
        }

        @Override public void close(String serverName) { closed.add(serverName); }
    }
}

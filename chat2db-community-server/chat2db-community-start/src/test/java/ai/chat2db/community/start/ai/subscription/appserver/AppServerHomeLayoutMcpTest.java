package ai.chat2db.community.start.ai.subscription.appserver;

import ai.chat2db.community.start.ai.subscription.appserver.internal.AppServerHomeLayout;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(value = 15, unit = TimeUnit.SECONDS)
class AppServerHomeLayoutMcpTest {

    @TempDir
    Path tempDir;

    @Test
    void withoutMcpDoesNotConfigureMcpServers() throws Exception {
        Path home = tempDir.resolve("home");
        Path work = tempDir.resolve("work");
        new AppServerHomeLayout(home, work).prepare(null);
        String toml = Files.readString(home.resolve("config.toml"));
        assertFalse(toml.contains("mcp_servers"));
        assertFalse(toml.contains("CHAT2DB_MCP_CAPABILITY"));
    }

    @Test
    void explicitlyDisablesPinnedDefaultNativeAndWorkspaceFeatures() throws Exception {
        Path home = tempDir.resolve("home-restrictive");
        Path work = tempDir.resolve("work-restrictive");
        new AppServerHomeLayout(home, work).prepare(null);
        String toml = Files.readString(home.resolve("config.toml"));

        assertTrue(toml.contains("shell_tool = false"));
        assertTrue(toml.contains("unified_exec = false"));
        assertTrue(toml.contains("shell_snapshot = false"));
        // CodeMode nested MCP hangs before tools/call; force direct function/MCP path.
        assertTrue(toml.contains("code_mode = false"));
        assertTrue(toml.contains("code_mode_host = false"));
        assertTrue(toml.contains("code_mode_only = false"));
        assertTrue(toml.contains("js_repl = false"));
        assertTrue(toml.contains("tool_search = false"));
        assertTrue(toml.contains("tool_search_always_defer_mcp_tools = false"));
        assertTrue(toml.contains("search_tool = false"));
        assertTrue(toml.contains("skills = false"));
        assertTrue(toml.contains("sandbox_mode = \"workspace-write\""));
        assertTrue(toml.contains("[sandbox_workspace_write]"));
        assertTrue(toml.contains("network_access = true"));
        assertFalse(toml.contains("[code_mode]"));
        assertFalse(toml.contains("direct_only_tool_namespaces"));
        assertTrue(toml.contains("workspace_dependencies = false"));
    }

    @Test
    void mcpConfigWritesLoopbackUrlAndEnvVarNameButNeverCapabilityValue() throws Exception {
        Path home = tempDir.resolve("home-mcp");
        Path work = tempDir.resolve("work-mcp");
        String secret = "a".repeat(64);
        AppServerMcpEndpoint mcp = new AppServerMcpEndpoint(
                "http://127.0.0.1:34567/mcp",
                "CHAT2DB_MCP_CAPABILITY",
                secret);
        new AppServerHomeLayout(home, work).prepare(mcp);
        String toml = Files.readString(home.resolve("config.toml"));

        assertTrue(toml.contains("http://127.0.0.1:34567/mcp"));
        assertTrue(toml.contains("127.0.0.1"));
        assertTrue(toml.contains("CHAT2DB_MCP_CAPABILITY"));
        assertTrue(toml.contains("X-Chat2DB-MCP-Capability")
                || toml.contains(AppServerMcpEndpoint.DEFAULT_CAPABILITY_HEADER));
        // Capability value must not appear in config.toml
        assertFalse(toml.contains(secret));
        assertFalse(toml.contains("a".repeat(32)));
    }

    @Test
    void mcpConfigExposesOnlyTheChat2dbDatabaseToolAllowlist() throws Exception {
        Path home = tempDir.resolve("home-tool-allowlist");
        Path work = tempDir.resolve("work-tool-allowlist");
        AppServerMcpEndpoint mcp = new AppServerMcpEndpoint(
                "http://127.0.0.1:34567/mcp",
                "CHAT2DB_MCP_CAPABILITY",
                "b".repeat(64));

        new AppServerHomeLayout(home, work).prepare(mcp);
        String toml = Files.readString(home.resolve("config.toml"));

        assertTrue(toml.contains("enabled_tools = ["));
        for (String tool : Set.of(
                "execute_sql", "list_all_datasources", "list_all_tables",
                "list_all_databases", "list_all_schemas", "get_tables_schema", "text2sql")) {
            assertTrue(toml.contains("\"" + tool + "\""), "missing allowed database tool " + tool);
        }
        assertFalse(toml.contains("\"list_mcp_resources\""));
        assertFalse(toml.contains("\"read_mcp_resource\""));
        // Shell must not appear in the MCP enabled_tools list.
        assertFalse(toml.matches("(?s).*enabled_tools\\s*=\\s*\\[[^\\]]*\"shell\".*"));
        assertTrue(toml.contains("code_mode = false"));
        assertTrue(toml.contains("code_mode_host = false"));
        assertTrue(toml.contains("tool_search_always_defer_mcp_tools = false"));
        assertFalse(toml.contains("[code_mode]"));
    }

    @Test
    void rejectsNonLoopbackMcpUrl() {
        assertThrows(IllegalArgumentException.class, () -> new AppServerMcpEndpoint(
                "http://10.0.0.5:8080/mcp",
                "CHAT2DB_MCP_CAPABILITY",
                "secret-value"));
        assertThrows(IllegalArgumentException.class, () -> new AppServerMcpEndpoint(
                "http://example.com/mcp",
                "CHAT2DB_MCP_CAPABILITY",
                "secret-value"));
    }

    @Test
    void rejectsNonEmptyWorkDirectoryRatherThanExposingExistingFiles() throws Exception {
        Path home = tempDir.resolve("home-non-empty");
        Path work = tempDir.resolve("work-non-empty");
        Files.createDirectories(work);
        Files.writeString(work.resolve("local-secret.txt"), "must-not-be-visible");

        assertThrows(RuntimeException.class, () -> new AppServerHomeLayout(home, work).prepare(null));
    }
}

package ai.chat2db.community.web.api.adapter.agent;

import ai.chat2db.community.domain.api.enums.agent.McpToolPolicy;
import ai.chat2db.community.domain.api.enums.agent.McpTransport;
import ai.chat2db.community.domain.api.model.agent.mcp.McpServerConfig;
import ai.chat2db.community.domain.api.model.agent.mcp.McpToolDescriptor;
import ai.chat2db.community.domain.api.service.agent.IMcpToolDiscovery;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Drives a real MCP server over stdio, so the SDK wiring is verified and not only mocked. */
class SdkMcpToolDiscoveryTest {

    @TempDir
    Path directory;

    private final SdkMcpToolDiscovery discovery = new SdkMcpToolDiscovery();

    @AfterEach
    void close() {
        discovery.close("fixture");
    }

    @Test
    void listsAndCallsToolsOfARealStdioServer() throws IOException {
        Assumptions.assumeTrue(nodeAvailable(), "node is required for the stdio fixture server");
        Path server = Path.of("src/test/resources/agent/mcp-fixture-server.cjs").toAbsolutePath();
        Assumptions.assumeTrue(Files.isRegularFile(server), "fixture server is missing");
        McpServerConfig config = new McpServerConfig("fixture", McpTransport.STDIO, "node",
                List.of(server.toString()), null, List.of(), List.of(), Map.of(), true,
                McpToolPolicy.ASK, List.of(), null, List.of(), null);

        List<McpToolDescriptor> tools;
        try {
            tools = discovery.discover(config);
        } catch (RuntimeException error) {
            StringBuilder chain = new StringBuilder(error.toString());
            Throwable cause = error.getCause();
            while (cause != null) {
                chain.append(" <- ").append(cause);
                cause = cause.getCause();
            }
            throw new AssertionError(chain.toString(), error);
        }

        assertEquals(1, tools.size());
        McpToolDescriptor echo = tools.get(0);
        assertEquals("echo", echo.name());
        assertTrue(echo.readOnlyHint(), "the server's readOnly annotation is carried into the catalogue");
        assertFalse(echo.destructiveHint());
        assertEquals("object", echo.parameters().get("type"));

        IMcpToolDiscovery.McpToolCallResult result = discovery.call(config, "echo", Map.of("text", "hello"));
        assertTrue(result.ok(), result.errorMessage());
        assertEquals("echo:hello", result.text());

        discovery.close("fixture");
        IMcpToolDiscovery.McpToolCallResult afterClose = discovery.call(config, "echo", Map.of("text", "again"));
        assertTrue(afterClose.ok(), "a dropped connection is re-established: " + afterClose.errorMessage());
        assertEquals("echo:again", afterClose.text());
    }

    @Test
    void reportsACommandThatCannotBeStarted() {
        McpServerConfig missing = new McpServerConfig("fixture", McpTransport.STDIO, "chat2db-missing-command",
                List.of(), null, List.of(), List.of(), Map.of(), true, McpToolPolicy.ASK, List.of(), null,
                List.of(), null);

        IMcpToolDiscovery.McpToolCallResult result = discovery.call(missing, "echo", Map.of("text", "hi"));

        assertFalse(result.ok());
        assertEquals("MCP_CALL_FAILED", result.errorCode());
    }

    private static boolean nodeAvailable() {
        try {
            Process process = new ProcessBuilder("node", "--version").redirectErrorStream(true).start();
            return process.waitFor() == 0;
        } catch (IOException error) {
            return false;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}

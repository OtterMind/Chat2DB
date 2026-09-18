package ai.chat2db.community.web.api.adapter.agent;

import ai.chat2db.community.domain.api.enums.agent.McpTransport;
import ai.chat2db.community.domain.api.model.agent.mcp.McpServerConfig;
import ai.chat2db.community.domain.api.model.agent.mcp.McpToolDescriptor;
import ai.chat2db.community.domain.api.service.agent.IMcpToolDiscovery;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.jackson.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Connects to external MCP servers with the MCP Java SDK. One connection per server is kept while it
 * is used; a connection is dropped when the configuration changes, the server is removed, or a call
 * fails, so the next attempt starts from a clean state instead of a half-broken transport.
 */
@Component
public class SdkMcpToolDiscovery implements IMcpToolDiscovery {

    private static final Duration DISCOVERY_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration CALL_TIMEOUT = Duration.ofMinutes(5);
    private static final int MAX_TEXT_LENGTH = 200_000;

    private final Map<String, Connection> connections = new ConcurrentHashMap<>();
    private final JacksonMcpJsonMapper mapper = new JacksonMcpJsonMapper(new ObjectMapper());

    @Override
    public List<McpToolDescriptor> discover(McpServerConfig config) {
        McpSchema.ListToolsResult result = client(config).listTools();
        List<McpToolDescriptor> tools = new ArrayList<>();
        for (McpSchema.Tool tool : result.tools()) {
            tools.add(new McpToolDescriptor(tool.name(), Objects.toString(tool.description(), ""),
                    schema(tool.inputSchema()), readOnly(tool), destructive(tool)));
        }
        return List.copyOf(tools);
    }

    @Override
    public McpToolCallResult call(McpServerConfig config, String toolName, Map<String, Object> arguments) {
        try {
            McpSchema.CallToolResult result = client(config)
                    .callTool(new McpSchema.CallToolRequest(toolName, arguments == null ? Map.of() : arguments));
            String text = flatten(result);
            if (Boolean.TRUE.equals(result.isError())) {
                return McpToolCallResult.failure("MCP_TOOL_ERROR", text);
            }
            return McpToolCallResult.success(text);
        } catch (RuntimeException error) {
            close(config.name());
            return McpToolCallResult.failure("MCP_CALL_FAILED",
                    "Calling " + toolName + " failed: " + Objects.toString(error.getMessage(),
                            error.getClass().getSimpleName()));
        }
    }

    @Override
    public void close(String serverName) {
        Connection connection = connections.remove(serverName);
        if (connection == null) return;
        try {
            connection.client().close();
        } catch (RuntimeException ignored) {
            // Closing a transport that already failed must not surface to the caller.
        }
    }

    private McpSyncClient client(McpServerConfig config) {
        Connection existing = connections.get(config.name());
        if (existing != null && existing.identity().equals(identity(config))) return existing.client();
        close(config.name());
        McpSyncClient client = McpClient.sync(transport(config))
                .requestTimeout(config.transport() == McpTransport.STDIO ? CALL_TIMEOUT : CALL_TIMEOUT)
                .clientInfo(new McpSchema.Implementation("chat2db", "Chat2DB", "1.0.0"))
                .build();
        try {
            client.initialize();
        } catch (RuntimeException error) {
            try {
                client.close();
            } catch (RuntimeException ignored) {
                // The initialization error is the useful one.
            }
            throw error;
        }
        connections.put(config.name(), new Connection(client, identity(config)));
        return client;
    }

    private McpClientTransport transport(McpServerConfig config) {
        if (config.transport() == McpTransport.STDIO) {
            Map<String, String> environment = new LinkedHashMap<>();
            for (String key : config.environmentKeys()) {
                String value = config.secrets().get(key);
                if (value != null) environment.put(key, value);
            }
            ServerParameters parameters = ServerParameters.builder(config.command())
                    .args(config.args())
                    .env(environment)
                    .build();
            return new StdioClientTransport(parameters, mapper);
        }
        return HttpClientStreamableHttpTransport.builder(config.url())
                .jsonMapper(mapper)
                .connectTimeout(DISCOVERY_TIMEOUT)
                .customizeRequest(request -> config.headerNames().forEach(name -> {
                    String value = config.secrets().get(name);
                    if (value != null) request.header(name, value);
                }))
                .build();
    }

    private static String identity(McpServerConfig config) {
        return String.join("\n", Objects.toString(config.transport(), ""), Objects.toString(config.commandLine(), ""),
                Objects.toString(config.url(), ""), String.join(",", config.environmentKeys()),
                String.join(",", config.headerNames()));
    }

    private static Map<String, Object> schema(McpSchema.JsonSchema schema) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", schema.type() == null ? "object" : schema.type());
        if (schema.properties() != null) result.put("properties", schema.properties());
        if (schema.required() != null && !schema.required().isEmpty()) result.put("required", schema.required());
        if (schema.additionalProperties() != null) result.put("additionalProperties", schema.additionalProperties());
        if (schema.defs() != null && !schema.defs().isEmpty()) result.put("$defs", schema.defs());
        if (schema.definitions() != null && !schema.definitions().isEmpty()) {
            result.put("definitions", schema.definitions());
        }
        return Map.copyOf(result);
    }

    private static boolean readOnly(McpSchema.Tool tool) {
        return tool.annotations() != null && Boolean.TRUE.equals(tool.annotations().readOnlyHint());
    }

    private static boolean destructive(McpSchema.Tool tool) {
        return tool.annotations() != null && Boolean.TRUE.equals(tool.annotations().destructiveHint());
    }

    /** MCP results carry content blocks; the model receives their text and a note about other blocks. */
    private static String flatten(McpSchema.CallToolResult result) {
        StringBuilder text = new StringBuilder();
        int skipped = 0;
        for (McpSchema.Content content : result.content()) {
            if (content instanceof McpSchema.TextContent block) {
                if (text.length() > 0) text.append('\n');
                text.append(block.text());
            } else {
                skipped++;
            }
        }
        if (skipped > 0) {
            text.append(text.length() > 0 ? "\n" : "").append('[').append(skipped)
                    .append(" non-text content block(s) omitted]");
        }
        if (result.structuredContent() != null) {
            if (text.length() > 0) text.append('\n');
            text.append(result.structuredContent());
        }
        String value = text.toString();
        if (value.isEmpty()) value = "The tool returned no content.";
        return value.length() > MAX_TEXT_LENGTH ? value.substring(0, MAX_TEXT_LENGTH)
                + "\n[truncated by Chat2DB]" : value;
    }

    private record Connection(McpSyncClient client, String identity) { }
}

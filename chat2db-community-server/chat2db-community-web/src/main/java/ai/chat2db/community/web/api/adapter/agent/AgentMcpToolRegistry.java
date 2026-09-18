package ai.chat2db.community.web.api.adapter.agent;

import ai.chat2db.community.domain.api.enums.agent.McpToolPolicy;
import ai.chat2db.community.domain.api.model.agent.mcp.McpServerState;
import ai.chat2db.community.domain.api.model.agent.mcp.McpToolDescriptor;
import ai.chat2db.community.domain.api.service.agent.IMcpServerService;
import ai.chat2db.community.domain.api.service.agent.IMcpServerService.McpServerRegistration;
import ai.chat2db.community.tools.model.agent.runtime.AgentToolAccess;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * The tools a model uses to manage external MCP servers. They are registered but inactive by default:
 * a model asks for them through {@code tool_search}, and each configuration change asks the user once
 * before it is written.
 */
@Component
public class AgentMcpToolRegistry {

    /** Group the tool_search loader may activate. */
    public static final String GROUP = "mcp-manage";
    public static final String LIST = "mcp_list_servers";
    public static final String ADD = "mcp_add_server";
    public static final String UPDATE = "mcp_update_server";
    public static final String REMOVE = "mcp_remove_server";
    public static final String TEST = "mcp_test_server";
    public static final String SET_POLICY = "mcp_set_policy";

    private static final String INVALID = "INVALID_ARGUMENT";
    private static final Set<String> FIELDS = Set.of("name", "transport", "command", "args", "url",
            "header_names", "environment_keys", "secrets", "enabled", "policy", "allowed_tools");

    private final IMcpServerService servers;
    private final Map<String, AgentToolAccess.Tool> definitions = new LinkedHashMap<>();

    public AgentMcpToolRegistry(IMcpServerService servers) {
        this.servers = servers;
        definitions.put(LIST, definition(LIST, "List the MCP servers the user configured, their transport, "
                + "whether they are enabled, their approval policy, how many tools each one exposes, and the "
                + "absolute path of the configuration file. Call this before adding or changing a server, and "
                + "whenever the user asks where the MCP configuration lives: report that path verbatim instead of "
                + "guessing one.", schema(Map.of(), List.of())));
        definitions.put(ADD, definition(ADD, "Add an external MCP server. stdio runs a local command that speaks MCP; "
                + "http connects to a URL. Give the environment variables or headers the server needs through "
                + "secrets, keyed by their names. The server is written to the configuration file and then connected "
                + "once: report the connection result to the user, and tell them the tools become available in the "
                + "conversation right away. The user is asked to approve this change first.",
                schema(Map.of(
                        "name", text("Short lowercase name, e.g. filesystem. Derived from the command or host when omitted."),
                        "transport", enumOf("stdio", "http", "How Chat2DB reaches the server. Defaults to stdio with a command, http with a url."),
                        "command", text("Executable to run for a stdio server, e.g. npx or uvx."),
                        "args", stringList("Arguments passed to the command, one entry each. Do not merge them into one string."),
                        "url", text("Streamable HTTP endpoint of the server, e.g. https://example.com/mcp. Plain HTTP is allowed only for localhost."),
                        "header_names", stringList("Names of the HTTP headers the server needs, e.g. Authorization."),
                        "environment_keys", stringList("Names of the environment variables the stdio command needs."),
                        "secrets", secrets("Values for the declared header names or environment variables. The user's own token goes here; it is stored in the configuration file and never echoed back."),
                        "enabled", bool("Whether the server's tools may run. Defaults to true."),
                        "policy", enumOf("ASK", "ALLOW", "Whether the server's tools ask the user before running. Defaults to ASK.")),
                        List.of())));
        definitions.put(UPDATE, definition(UPDATE, "Change an existing MCP server. Only the fields you pass are changed; "
                + "omitting a field keeps its current value. Changing the command, url, arguments, environment variable "
                + "names or header names invalidates the previous approval, so the user is asked again. The user is asked "
                + "to approve this change first.",
                schema(Map.of(
                        "name", text("Name of the server to change."),
                        "transport", enumOf("stdio", "http", "Switch the transport."),
                        "command", text("New executable for a stdio server."),
                        "args", stringList("Replacement arguments for the stdio command."),
                        "url", text("New streamable HTTP endpoint."),
                        "header_names", stringList("Replacement HTTP header names."),
                        "environment_keys", stringList("Replacement environment variable names."),
                        "secrets", secrets("Values to set. A blank value removes that entry."),
                        "enabled", bool("Enable or disable the server."),
                        "policy", enumOf("ASK", "ALLOW", "Whether the server's tools ask before running.")),
                        List.of("name"))));
        definitions.put(REMOVE, definition(REMOVE, "Remove an MCP server and stop it. Its tools disappear from the "
                + "conversation. The user is asked to approve this change first.",
                schema(Map.of("name", text("Name of the server to remove.")), List.of("name"))));
        definitions.put(TEST, definition(TEST, "Connect to a configured MCP server and list the tools it currently "
                + "advertises without changing the configuration. Use it after adding or changing a server, and to "
                + "explain a connection failure to the user.",
                schema(Map.of("name", text("Name of the server to test.")), List.of("name"))));
        definitions.put(SET_POLICY, definition(SET_POLICY, "Change how an MCP server's tools are approved, or forget "
                + "tools the user allowed earlier. Pass policy ALLOW to stop asking for the whole server, or policy "
                + "ASK with allowed_tools to keep only the listed tools approved. The user is asked to approve this "
                + "change first.",
                schema(Map.of(
                        "name", text("Name of the server to change."),
                        "policy", enumOf("ASK", "ALLOW", "Approval policy for every tool of this server."),
                        "allowed_tools", stringList("Exact tool names that stay approved without asking. Pass an empty list to ask for every tool again.")),
                        List.of("name"))));
    }

    public List<AgentToolAccess.Tool> definitions() {
        return List.copyOf(definitions.values());
    }

    public Set<String> names() {
        return Set.copyOf(definitions.keySet());
    }

    public boolean contains(String toolName) {
        return definitions.containsKey(toolName);
    }

    public AgentMcpResponse execute(String toolName, Map<String, Object> arguments) {
        try {
            return switch (toolName) {
                case LIST -> list();
                case ADD -> add(arguments);
                case UPDATE -> update(arguments);
                case REMOVE -> remove(arguments);
                case TEST -> test(arguments);
                case SET_POLICY -> setPolicy(arguments);
                default -> AgentMcpResponse.failure("UNKNOWN_TOOL", "toolName", "Unknown MCP tool: " + toolName);
            };
        } catch (IllegalArgumentException error) {
            return AgentMcpResponse.failure(INVALID, null, error.getMessage());
        } catch (IllegalStateException error) {
            return AgentMcpResponse.failure("MCP_UNAVAILABLE", null, Objects.toString(error.getMessage(),
                    "MCP configuration is unavailable"));
        }
    }

    private AgentMcpResponse list() {
        List<McpServerState> states = servers.list();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("configPath", servers.configPath());
        data.put("servers", states);
        data.put("hint", states.isEmpty()
                ? "No MCP server is configured yet. Ask the user for the command or URL they want to connect."
                : "Report the configPath verbatim when the user asks where the configuration lives.");
        return AgentMcpResponse.success(data);
    }

    private AgentMcpResponse add(Map<String, Object> arguments) {
        rejectUnknownFields(arguments);
        McpServerState created = servers.add(registration(arguments));
        return AgentMcpResponse.success(connect(created.name(), "Server added."));
    }

    private AgentMcpResponse update(Map<String, Object> arguments) {
        rejectUnknownFields(arguments);
        String name = text(arguments, "name", true);
        McpServerState updated = servers.update(name, registration(arguments));
        return AgentMcpResponse.success(connect(updated.name(), "Server updated."));
    }

    private AgentMcpResponse remove(Map<String, Object> arguments) {
        rejectUnknownFields(arguments);
        String name = text(arguments, "name", true);
        servers.remove(name);
        return AgentMcpResponse.success(Map.of("removed", name));
    }

    private AgentMcpResponse test(Map<String, Object> arguments) {
        rejectUnknownFields(arguments);
        String name = text(arguments, "name", true);
        McpServerState state;
        try {
            state = servers.refreshTools(name);
        } catch (RuntimeException error) {
            return AgentMcpResponse.failure("MCP_CONNECTION_FAILED", "name",
                    "Could not connect to MCP server '" + name + "': " + Objects.toString(error.getMessage(),
                            error.getClass().getSimpleName())
                            + ". Check the command or URL, the required environment variables and headers, and whether "
                            + "the server needs an interactive OAuth login (not supported yet).");
        }
        List<Map<String, Object>> tools = servers.require(name).tools().stream()
                .map(tool -> toolView(tool)).toList();
        return AgentMcpResponse.success(Map.of("server", state, "tools", tools));
    }

    private AgentMcpResponse setPolicy(Map<String, Object> arguments) {
        rejectUnknownFields(arguments);
        String name = text(arguments, "name", true);
        McpToolPolicy policy = policy(arguments.get("policy"));
        List<String> allowed = stringList(arguments.get("allowed_tools"));
        McpServerState state = servers.setPolicy(name, policy, arguments.containsKey("allowed_tools") ? allowed : null);
        return AgentMcpResponse.success(Map.of("server", state));
    }

    private Map<String, Object> connect(String name, String message) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("message", message);
        try {
            McpServerState state = servers.refreshTools(name);
            result.put("connected", true);
            result.put("server", state);
            result.put("tools", servers.require(name).tools().stream().map(AgentMcpToolRegistry::toolView).toList());
            result.put("hint", "Tell the user the tools are available now; they may need to confirm the approval card first.");
        } catch (RuntimeException error) {
            result.put("connected", false);
            result.put("error", Objects.toString(error.getMessage(), error.getClass().getSimpleName()));
            result.put("hint", "The configuration is saved. Report the connection error to the user and suggest "
                    + "checking the command or URL, the required environment variables or headers, and whether the "
                    + "server expects an interactive OAuth login (not supported yet).");
        }
        return result;
    }

    private static Map<String, Object> toolView(McpToolDescriptor tool) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("name", tool.name());
        view.put("description", tool.description());
        view.put("readOnly", tool.readOnlyHint());
        view.put("destructive", tool.destructiveHint());
        return view;
    }

    private McpServerRegistration registration(Map<String, Object> arguments) {
        return new McpServerRegistration(text(arguments, "name", false),
                text(arguments, "transport", false),
                text(arguments, "command", false),
                arguments.containsKey("args") ? stringList(arguments.get("args")) : null,
                text(arguments, "url", false),
                arguments.containsKey("header_names") ? stringList(arguments.get("header_names")) : null,
                arguments.containsKey("environment_keys") ? stringList(arguments.get("environment_keys")) : null,
                secrets(arguments.get("secrets")),
                arguments.containsKey("enabled") ? bool(arguments.get("enabled")) : null,
                arguments.containsKey("policy") ? policy(arguments.get("policy")) : null);
    }

    private static void rejectUnknownFields(Map<String, Object> arguments) {
        for (String field : arguments.keySet()) {
            if (!FIELDS.contains(field)) {
                throw new IllegalArgumentException("Unknown field '" + field + "'. Allowed fields: "
                        + String.join(", ", FIELDS) + ".");
            }
        }
    }

    private static String text(Map<String, Object> arguments, String field, boolean required) {
        Object value = arguments.get(field);
        if (value == null) {
            if (required) throw new IllegalArgumentException(field + " is required");
            return null;
        }
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException(field + " must be a non-empty string");
        }
        return text.trim();
    }

    private static boolean bool(Object value) {
        if (value instanceof Boolean flag) return flag;
        if (value instanceof String text) return Boolean.parseBoolean(text.trim());
        throw new IllegalArgumentException("enabled must be true or false");
    }

    private static McpToolPolicy policy(Object value) {
        if (value == null) return null;
        if (value instanceof String text) {
            try {
                return McpToolPolicy.valueOf(text.trim().toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException error) {
                throw new IllegalArgumentException("policy must be ASK or ALLOW");
            }
        }
        throw new IllegalArgumentException("policy must be ASK or ALLOW");
    }

    private static List<String> stringList(Object value) {
        if (value == null) return List.of();
        if (value instanceof String text) return text.isBlank() ? List.of() : List.of(text);
        if (!(value instanceof List<?> items)) throw new IllegalArgumentException("expected a list of strings");
        List<String> values = new ArrayList<>();
        for (Object item : items) {
            if (item == null) continue;
            if (!(item instanceof String text)) throw new IllegalArgumentException("expected a list of strings");
            if (!text.isBlank()) values.add(text);
        }
        return List.copyOf(values);
    }

    private static Map<String, String> secrets(Object value) {
        if (value == null) return null;
        if (!(value instanceof Map<?, ?> items)) {
            throw new IllegalArgumentException("secrets must be an object of name/value pairs");
        }
        Map<String, String> secrets = new LinkedHashMap<>();
        items.forEach((key, entry) -> {
            if (!(key instanceof String name) || name.isBlank()) {
                throw new IllegalArgumentException("secrets keys must be names");
            }
            secrets.put(name.trim(), entry == null ? "" : String.valueOf(entry));
        });
        return Map.copyOf(secrets);
    }

    private static AgentToolAccess.Tool definition(String name, String description, Map<String, Object> parameters) {
        return new AgentToolAccess.Tool(name, description, parameters, null, List.of(), GROUP, false);
    }

    private static Map<String, Object> schema(Map<String, Object> properties, List<String> required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        if (!required.isEmpty()) schema.put("required", required);
        schema.put("additionalProperties", false);
        return schema;
    }

    private static Map<String, Object> text(String description) {
        return Map.of("type", "string", "minLength", 1, "description", description);
    }

    private static Map<String, Object> bool(String description) {
        return Map.of("type", "boolean", "description", description);
    }

    private static Map<String, Object> enumOf(String first, String second, String description) {
        return Map.of("type", "string", "enum", List.of(first, second), "description", description);
    }

    private static Map<String, Object> stringList(String description) {
        return Map.of("type", "array", "items", Map.of("type", "string"), "description", description);
    }

    private static Map<String, Object> secrets(String description) {
        return Map.of("type", "object", "additionalProperties", Map.of("type", "string"),
                "description", description);
    }

    private static Set<String> ordered(String... values) {
        return new LinkedHashSet<>(List.of(values));
    }
}

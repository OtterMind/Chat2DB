package ai.chat2db.community.start.ai.subscription.appserver;

import ai.chat2db.community.start.ai.subscription.appserver.internal.AppServerHomeLayout;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Shared policy for the only tools exposed to subscription turns. */
public final class Chat2dbMcpToolPolicy {

    public static final Set<String> DATABASE_TOOLS = Set.of(
            "execute_sql",
            "list_all_datasources",
            "list_all_tables",
            "list_all_databases",
            "list_all_schemas",
            "get_tables_schema",
            "text2sql");

    public static final List<String> ORDERED_DATABASE_TOOLS = DATABASE_TOOLS.stream().sorted().toList();

    /**
     * Pinned Codex v0.144.6 injects these metadata helpers whenever a direct MCP server exists.
     * Chat2DB advertises no resources, so these calls are side-effect free and may only inspect
     * the isolated Chat2DB MCP registration. They are not forwarded to the database tool kernel.
     */
    public static final Set<String> PINNED_RESOURCE_METADATA_TOOLS = Set.of(
            "list_mcp_resources",
            "list_mcp_resource_templates",
            "read_mcp_resource");

    /**
     * Same product surface as the API-key AI tool path ({@code AiToolMcpAdapter}):
     * operate on already-configured datasources only. Connection create/edit stays in the UI.
     * <p>
     * With ToolSearchAlwaysDeferMcpTools (often forced on under ChatGPT auth), the model reaches
     * MCP via Responses {@code FunctionCall} with {@code namespace=mcp__chat2db_subscription}.
     * App-server surfaces those as {@code functionCall} and/or {@code mcpToolCall} items. Both
     * must be allowed for the allowlisted tools so Chat2DB does not interruptTurn (which Codex
     * reports as “user cancelled MCP tool call” and never POSTs HTTP tools/call).
     */
    public static final String THREAD_DEVELOPER_INSTRUCTIONS =
            "You are embedded in Chat2DB Community. Follow the same database-tool rules as the API-key AI path. "
                    + "Allowed tools (chat2db_subscription MCP only, direct function/MCP calls): "
                    + "list_all_datasources, list_all_databases, list_all_schemas, list_all_tables, "
                    + "get_tables_schema, execute_sql, text2sql. "
                    + "Call these tools only as direct MCP/function tool calls against server "
                    + "chat2db_subscription (or namespace mcp__chat2db_subscription). "
                    + "UI selection priority: when the user message includes a Current UI selection block with "
                    + "dataSourceId and/or databaseName, use those ids first for list_all_tables / "
                    + "get_tables_schema / execute_sql / list_all_databases. Do not call list_all_datasources "
                    + "first unless the selection is missing. Only expand to other datasources if the selected "
                    + "one is unreachable, empty, or clearly lacks the needed tables/data — and say briefly "
                    + "why you are expanding. "
                    + "Prefer writing SQL yourself after list_all_tables/get_tables_schema, then execute_sql. "
                    + "Avoid text2sql on subscription turns: you are already the NL2SQL model; text2sql is a "
                    + "legacy nested API-key helper and may return TEXT2SQL_UNAVAILABLE_ON_SUBSCRIPTION. "
                    + "Do not use exec, code_mode, shell, apply_patch, JS REPL, or tools.mcp__… wrappers. "
                    + "Never create, update, delete, or save a datasource/connection; there is no such tool. "
                    + "If the user asks to add a JDBC/MySQL connection, tell them to use Chat2DB's connection UI; "
                    + "you may parse the URL into a field checklist and, after they create the connection, "
                    + "use list_all_datasources / execute_sql. "
                    + "Never call list_mcp_resources, list_mcp_resource_templates, or read_mcp_resource; "
                    + "this isolated server intentionally exposes no MCP resources. "
                    + "Do not load external skills or agent instructions for tool routing.";

    /**
     * Appends a machine-readable UI selection block for the model prompt when the renderer
     * provided a cascader selection. Safe: ids and names only, never credentials.
     */
    public static String formatUiSelectionContext(Long dataSourceId, String databaseName, String schemaName) {
        if (dataSourceId == null
                && (databaseName == null || databaseName.isBlank())
                && (schemaName == null || schemaName.isBlank())) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Current UI selection (priority target — use first, expand only if needed):\n");
        if (dataSourceId != null) {
            sb.append("dataSourceId=").append(dataSourceId).append('\n');
        }
        if (databaseName != null && !databaseName.isBlank()) {
            sb.append("databaseName=").append(databaseName.trim()).append('\n');
        }
        if (schemaName != null && !schemaName.isBlank()) {
            sb.append("schemaName=").append(schemaName.trim()).append('\n');
        }
        sb.append("Prefer tools with these identifiers. Avoid listing other datasources unless the ")
                .append("selected one cannot answer the question.\n");
        return sb.toString();
    }

    /**
     * Native item types that must never run unless they are the allowlisted Chat2DB MCP surface.
     * Note: {@code functionCall} is handled specially — only MCP namespace/name allowlist passes.
     */
    public static final Set<String> DENIED_ITEM_TYPES = Set.of(
            "commandExecution",
            "fileChange",
            "localShellCall",
            "shell",
            "webSearch",
            "imageGeneration");

    /** Tool names that are never allowed (shell/patch surfaces). */
    public static final Set<String> DENIED_TOOL_NAMES = Set.of(
            "shell",
            "bash",
            "python",
            "apply_patch",
            "applyPatch",
            "exec_command",
            "local_shell",
            "exec");

    private static final String MCP_NAMESPACE_PREFIX = "mcp__" + AppServerHomeLayout.MCP_SERVER_ID;

    private Chat2dbMcpToolPolicy() {
    }

    public static boolean isPinnedResourceMetadataCall(String server, String tool) {
        return PINNED_RESOURCE_METADATA_TOOLS.contains(tool)
                && ("codex".equals(server) || AppServerHomeLayout.MCP_SERVER_ID.equals(server));
    }

    /**
     * {@code true} when the item is a code-mode {@code exec} transport call.
     * Subscription runtime keeps CodeMode off, so these are always denied by
     * {@link #isDeniedNativeOrCodeModeItem}.
     */
    public static boolean isCodeModeExecTransport(JsonNode item) {
        if (item == null || !item.isObject()) {
            return false;
        }
        String type = item.path("type").asText("").trim();
        if (!"customToolCall".equals(type) && !"exec".equals(type) && !"codeMode".equals(type)) {
            return false;
        }
        String name = firstNonBlank(item.path("name").asText(null), item.path("tool").asText(null));
        return name == null || name.isBlank() || "exec".equalsIgnoreCase(name.trim());
    }

    /**
     * Allowlisted Chat2DB MCP tool invocation, either as {@code mcpToolCall} or as Responses-style
     * {@code functionCall} with namespace {@code mcp__chat2db_subscription}.
     */
    public static boolean isAllowlistedChat2dbMcpInvocation(JsonNode item) {
        if (item == null || !item.isObject()) {
            return false;
        }
        String type = item.path("type").asText("").trim();
        if ("mcpToolCall".equals(type)) {
            String server = item.path("server").asText("").trim();
            String tool = item.path("tool").asText("").trim();
            if (AppServerHomeLayout.MCP_SERVER_ID.equals(server) && DATABASE_TOOLS.contains(tool)) {
                return true;
            }
            // Some app-server builds put the full mcp__server__tool id in name/tool.
            return isAllowlistedMcpToolName(firstNonBlank(tool, item.path("name").asText(null)));
        }
        if ("functionCall".equals(type) || "function_call".equals(type)) {
            String namespace = firstNonBlank(
                    item.path("namespace").asText(null),
                    item.path("server").asText(null));
            String name = firstNonBlank(item.path("name").asText(null), item.path("tool").asText(null));
            if (namespace != null && name != null) {
                String ns = namespace.trim();
                if ((MCP_NAMESPACE_PREFIX.equals(ns) || AppServerHomeLayout.MCP_SERVER_ID.equals(ns))
                        && DATABASE_TOOLS.contains(name.trim())) {
                    return true;
                }
            }
            return isAllowlistedMcpToolName(name);
        }
        return false;
    }

    public static boolean isDeniedNativeOrCodeModeItem(JsonNode item) {
        if (item == null || !item.isObject()) {
            return true;
        }
        // Allowlisted Chat2DB MCP function/MCP calls must not be treated as denied natives.
        if (isAllowlistedChat2dbMcpInvocation(item)) {
            return false;
        }
        String type = item.path("type").asText("").trim();
        if (DENIED_ITEM_TYPES.contains(type)) {
            return true;
        }
        // Non-allowlisted functionCall remains fail-closed (generic OpenAI tools, etc.).
        // tool_search is a Codex discovery helper used when AlwaysDefer is on — not a native danger.
        if ("functionCall".equals(type) || "function_call".equals(type)) {
            String fn = firstNonBlank(item.path("name").asText(null), item.path("tool").asText(null));
            if (fn != null && ("tool_search".equals(fn) || "toolSearch".equals(fn))) {
                return false;
            }
            return true;
        }
        // CodeMode / exec nested MCP is disabled for subscription; fail closed.
        if ("customToolCall".equals(type) || "exec".equals(type) || "codeMode".equals(type)) {
            return true;
        }
        String name = firstNonBlank(item.path("name").asText(null), item.path("tool").asText(null));
        if (name != null && DENIED_TOOL_NAMES.contains(name)) {
            return true;
        }
        String input = firstNonBlank(item.path("input").asText(null), item.path("arguments").asText(null));
        if (input != null) {
            String lower = input.toLowerCase(Locale.ROOT);
            if (lower.contains("exec_command") || lower.contains("apply_patch")
                    || lower.contains("tools.shell") || lower.contains("tools.local_shell")
                    || lower.contains("tools.mcp__")) {
                return true;
            }
        }
        return false;
    }

    public static boolean isDirectChat2dbMcpToolCall(JsonNode item) {
        return isAllowlistedChat2dbMcpInvocation(item);
    }

    private static boolean isAllowlistedMcpToolName(String name) {
        if (name == null || name.isBlank()) {
            return false;
        }
        String n = name.trim();
        if (DATABASE_TOOLS.contains(n)) {
            return false; // bare tool name without server/namespace is not enough
        }
        // mcp__chat2db_subscription__list_all_datasources
        String prefix = MCP_NAMESPACE_PREFIX + "__";
        if (n.startsWith(prefix)) {
            String tool = n.substring(prefix.length());
            return DATABASE_TOOLS.contains(tool);
        }
        // Concatenated form seen in some logs: mcp__chat2db_subscriptionlist_all_datasources
        if (n.startsWith(MCP_NAMESPACE_PREFIX)) {
            String rest = n.substring(MCP_NAMESPACE_PREFIX.length());
            if (rest.startsWith("__")) {
                rest = rest.substring(2);
            }
            return DATABASE_TOOLS.contains(rest);
        }
        return false;
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) {
            return a.trim();
        }
        if (b != null && !b.isBlank()) {
            return b.trim();
        }
        return null;
    }
}

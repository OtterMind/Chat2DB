package ai.chat2db.community.start.ai.subscription.runtime;

import ai.chat2db.community.start.ai.subscription.routing.tool.Chat2dbToolExecutor;
import ai.chat2db.community.web.api.mcp.adapter.AiToolMcpAdapter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Adapts the existing Community database tools to the dedicated subscription MCP bridge. */
public final class SubscriptionMcpToolExecutor implements Chat2dbToolExecutor {

    private final AiToolMcpAdapter adapter;
    private final ObjectMapper mapper;

    public SubscriptionMcpToolExecutor(AiToolMcpAdapter adapter, ObjectMapper mapper) {
        this.adapter = Objects.requireNonNull(adapter, "adapter");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    @Override
    public String execute(String toolName, String argumentsJson) throws Exception {
        JsonNode args = mapper.readTree(argumentsJson == null || argumentsJson.isBlank() ? "{}" : argumentsJson);
        if (args == null || !args.isObject()) {
            throw new IllegalArgumentException("tool arguments must be an object");
        }
        return switch (toolName) {
            case "list_all_datasources" -> adapter.listAllDataSourcesStrict();
            case "list_all_tables" -> adapter.listAllTablesStrict(
                    requiredLong(args, "dataSourceId"), requiredText(args, "databaseName"), text(args, "schemaName"));
            case "list_all_databases" -> adapter.listAllDatabasesStrict(requiredLong(args, "dataSourceId"));
            case "list_all_schemas" -> adapter.listAllSchemasStrict(
                    requiredText(args, "targetDatabaseName"), requiredLong(args, "dataSourceId"));
            case "execute_sql" -> adapter.executeSqlStrict(
                    requiredText(args, "sql"), integer(args, "pageSize"), requiredLong(args, "dataSourceId"),
                    requiredText(args, "databaseName"), text(args, "schemaName"));
            case "get_tables_schema" -> adapter.getTablesSchemaStrict(
                    requiredTextList(args, "tableNames"),
                    requiredLong(args, "dataSourceId"), requiredText(args, "databaseName"), text(args, "schemaName"));
            case "text2sql" -> executeText2Sql(args);
            default -> throw new IllegalArgumentException("tool is not allowlisted");
        };
    }

    /**
     * Subscription turns already run on the Codex/ChatGPT model. Nested API-key
     * {@code chatSync} for text2sql often fails (no key model) in ~tens of ms and the tool
     * kernel then fences the attempt as {@code TOOL_OUTCOME_UNKNOWN}, killing the whole turn
     * after earlier list/query tools succeeded. Do not nest another AI call: return guidance
     * so the model continues with schema tools + {@code execute_sql}.
     */
    private String executeText2Sql(JsonNode args) {
        String question = requiredText(args, "question");
        Long dataSourceId = longValue(args, "dataSourceId");
        String databaseName = text(args, "databaseName");
        String schemaName = text(args, "schemaName");
        // Keep the adapter reference used so tests and wiring stay valid; do not invoke nested AI.
        Objects.requireNonNull(adapter, "adapter");
        return "TEXT2SQL_DELEGATED_TO_SUBSCRIPTION_MODEL: You are already the subscription AI. "
                + "Do not call text2sql again. Use list_all_tables/get_tables_schema if needed, "
                + "write the SQL yourself, then call execute_sql. "
                + "question=" + question
                + (dataSourceId == null ? "" : " dataSourceId=" + dataSourceId)
                + (databaseName == null || databaseName.isBlank() ? "" : " databaseName=" + databaseName)
                + (schemaName == null || schemaName.isBlank() ? "" : " schemaName=" + schemaName);
    }

    private static String requiredText(JsonNode args, String name) {
        String value = text(args, name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }

    private static String text(JsonNode args, String name) {
        JsonNode value = args.get(name);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static Long requiredLong(JsonNode args, String name) {
        Long value = longValue(args, name);
        if (value == null) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }

    private static Long longValue(JsonNode args, String name) {
        JsonNode value = args.get(name);
        return value == null || value.isNull() || !value.canConvertToLong() ? null : value.asLong();
    }

    private static Integer integer(JsonNode args, String name) {
        JsonNode value = args.get(name);
        return value == null || value.isNull() || !value.canConvertToInt() ? null : value.asInt();
    }

    private static List<String> requiredTextList(JsonNode args, String name) {
        JsonNode value = args.get(name);
        if (value == null || !value.isArray() || value.isEmpty()) {
            throw new IllegalArgumentException(name + " must be a non-empty array");
        }
        List<String> result = new ArrayList<>(value.size());
        for (JsonNode item : value) {
            if (!item.isTextual() || item.asText().isBlank()) {
                throw new IllegalArgumentException(name + " must contain only non-blank strings");
            }
            result.add(item.asText());
        }
        return List.copyOf(result);
    }
}

package ai.chat2db.community.start.ai.subscription.runtime;

import ai.chat2db.community.start.ai.subscription.routing.tool.Chat2dbToolExecutor;
import ai.chat2db.community.web.api.mcp.adapter.AiToolMcpAdapter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Adapts the existing Community database tools to the dedicated subscription MCP bridge. */
public final class SubscriptionMcpToolExecutor implements Chat2dbToolExecutor {

    /**
     * Hard cap for metadata discovery tools so a single unreachable datasource cannot stall
     * the whole subscription turn for minutes (observed ~150s hangs on list_all_databases).
     */
    static final long METADATA_TOOL_TIMEOUT_MS = 15_000L;

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
            case "list_all_tables" -> {
                // Validate args before timeout wrap so client/schema errors still throw.
                Long dataSourceId = requiredLong(args, "dataSourceId");
                String databaseName = requiredText(args, "databaseName");
                String schemaName = text(args, "schemaName");
                yield withMetadataTimeout(toolName, args,
                        () -> adapter.listAllTablesStrict(dataSourceId, databaseName, schemaName));
            }
            case "list_all_databases" -> {
                Long dataSourceId = requiredLong(args, "dataSourceId");
                yield withMetadataTimeout(toolName, args,
                        () -> adapter.listAllDatabasesStrict(dataSourceId));
            }
            case "list_all_schemas" -> {
                String targetDatabaseName = requiredText(args, "targetDatabaseName");
                Long dataSourceId = requiredLong(args, "dataSourceId");
                yield withMetadataTimeout(toolName, args,
                        () -> adapter.listAllSchemasStrict(targetDatabaseName, dataSourceId));
            }
            case "execute_sql" -> adapter.executeSqlStrict(
                    requiredText(args, "sql"), integer(args, "pageSize"), requiredLong(args, "dataSourceId"),
                    requiredText(args, "databaseName"), text(args, "schemaName"));
            case "get_tables_schema" -> {
                List<String> tableNames = requiredTextList(args, "tableNames");
                Long dataSourceId = requiredLong(args, "dataSourceId");
                String databaseName = requiredText(args, "databaseName");
                String schemaName = text(args, "schemaName");
                yield withMetadataTimeout(toolName, args,
                        () -> adapter.getTablesSchemaStrict(tableNames, dataSourceId, databaseName, schemaName));
            }
            case "text2sql" -> executeText2Sql(args);
            default -> throw new IllegalArgumentException("tool is not allowlisted");
        };
    }

    /**
     * Soft-timeout metadata tools: return an ERROR string so the model can pivot instead of
     * fencing the whole attempt. Also lowers DriverManager login timeout for the call window.
     */
    static String withMetadataTimeout(String toolName, JsonNode args, Callable<String> call) {
        Long dataSourceId = longValue(args, "dataSourceId");
        int previousLoginTimeout = DriverManager.getLoginTimeout();
        ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "subscription-mcp-metadata-" + toolName);
            thread.setDaemon(true);
            return thread;
        });
        try {
            DriverManager.setLoginTimeout((int) Math.max(1L, METADATA_TOOL_TIMEOUT_MS / 1000L));
            Future<String> future = executor.submit(call);
            return future.get(METADATA_TOOL_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException timedOut) {
            return "ERROR: " + toolName + " timed out after "
                    + (METADATA_TOOL_TIMEOUT_MS / 1000L) + "s"
                    + (dataSourceId == null ? "" : " for dataSourceId=" + dataSourceId)
                    + ". Prefer the UI-selected datasource if different, or report the connection is slow/unreachable.";
        } catch (ExecutionException execution) {
            Throwable cause = execution.getCause() == null ? execution : execution.getCause();
            // Argument / contract errors should still surface as exceptions to callers/tests.
            if (cause instanceof IllegalArgumentException illegalArgument) {
                throw illegalArgument;
            }
            return "ERROR: " + toolName + " failed (" + cause.getClass().getSimpleName() + ")"
                    + (dataSourceId == null ? "" : " dataSourceId=" + dataSourceId)
                    + ". Prefer another approach or the UI-selected datasource.";
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return "ERROR: " + toolName + " interrupted"
                    + (dataSourceId == null ? "" : " dataSourceId=" + dataSourceId) + ".";
        } finally {
            DriverManager.setLoginTimeout(previousLoginTimeout);
            executor.shutdownNow();
        }
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

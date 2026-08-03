package ai.chat2db.community.web.api.mcp.adapter;


import ai.chat2db.community.tools.annotation.NotCliRuntime;
import ai.chat2db.community.web.api.enums.ai.QuestionTypeEnum;
import ai.chat2db.community.web.api.model.request.ai.ChatRequest;
import ai.chat2db.community.web.api.adapter.ai.AiChatStreamAdapter;
import ai.chat2db.community.web.api.adapter.ai.AiToolAdapter;
import ai.chat2db.community.tools.model.Context;
import ai.chat2db.community.tools.util.ContextUtils;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

@Component
@Slf4j
@NotCliRuntime
public class AiToolMcpAdapter {

    private final AiToolAdapter aiToolAdapter;

    private final AiChatStreamAdapter aiChatStreamAdapter;

    public AiToolMcpAdapter(AiToolAdapter aiToolAdapter, AiChatStreamAdapter aiChatStreamAdapter) {
        this.aiToolAdapter = aiToolAdapter;
        this.aiChatStreamAdapter = aiChatStreamAdapter;
    }

    @Tool(name = "list_all_datasources", description = "List available Chat2DB datasources. Use this first, then pass the returned dataSourceId to datasource-scoped tools.")
    public String listAllDataSources() {
        return invoke("list_all_datasources", aiToolAdapter::listAllDataSources);
    }

    /**
     * Strict entry point for the subscription MCP boundary. Exceptions are deliberately
     * propagated so the subscription tool journal can mark the outcome unknown without
     * logging or returning provider/database exception text.
     */
    public String listAllDataSourcesStrict() {
        return aiToolAdapter.listAllDataSources(buildToolContext());
    }

    @Tool(name = "list_all_tables", description = "List all tables in a target database. Call list_all_datasources and list_all_databases first, then pass dataSourceId and databaseName explicitly.")
    public String listAllTables(
            @ToolParam(description = "Datasource id returned by list_all_datasources.", required = true) Long dataSourceId,
            @ToolParam(description = "Target database name returned by list_all_databases.", required = true) String databaseName,
            @ToolParam(description = "Optional target schema name returned by list_all_schemas.", required = false) String schemaName) {
        return invoke("list_all_tables", toolContext -> aiToolAdapter.listAllTables(dataSourceId, databaseName, schemaName, toolContext));
    }

    public String listAllTablesStrict(Long dataSourceId, String databaseName, String schemaName) {
        return aiToolAdapter.listAllTables(dataSourceId, databaseName, schemaName, buildToolContext());
    }

    @Tool(name = "list_all_databases", description = "List all databases available on a target Chat2DB datasource. Call list_all_datasources first, then pass dataSourceId explicitly.")
    public String listAllDatabases(
            @ToolParam(description = "Datasource id returned by list_all_datasources.", required = true) Long dataSourceId) {
        return invoke("list_all_databases", toolContext -> aiToolAdapter.listAllDatabases(dataSourceId, toolContext));
    }

    public String listAllDatabasesStrict(Long dataSourceId) {
        return aiToolAdapter.listAllDatabases(dataSourceId, buildToolContext());
    }

    @Tool(name = "list_all_schemas", description = "List all schemas in a target database. Call list_all_datasources and list_all_databases first, then pass targetDatabaseName and dataSourceId explicitly.")
    public String listAllSchemas(
            @ToolParam(description = "Target database name returned by list_all_databases.", required = true) String targetDatabaseName,
            @ToolParam(description = "Datasource id returned by list_all_datasources.", required = true) Long dataSourceId) {
        return invoke(
                "list_all_schemas",
                toolContext -> aiToolAdapter.listAllSchemas(targetDatabaseName, dataSourceId, toolContext));
    }

    public String listAllSchemasStrict(String targetDatabaseName, Long dataSourceId) {
        return aiToolAdapter.listAllSchemas(targetDatabaseName, dataSourceId, buildToolContext());
    }

    @Tool(name = "execute_sql", description = "Execute SQL against the target database and return a concise result. Pass dataSourceId and databaseName explicitly.")
    public String executeSql(
            @ToolParam(description = "SQL to execute", required = true) String sql,
            @ToolParam(description = "Optional page size for SELECT. Default 200, max 500", required = false) Integer pageSize,
            @ToolParam(description = "Datasource id returned by list_all_datasources.", required = true) Long dataSourceId,
            @ToolParam(description = "Target database name returned by list_all_databases.", required = true) String databaseName,
            @ToolParam(description = "Optional target schema name returned by list_all_schemas.", required = false) String schemaName) {
        return invoke(
                "execute_sql",
                toolContext -> aiToolAdapter.executeSql(sql, pageSize, dataSourceId, databaseName, schemaName, toolContext));
    }

    public String executeSqlStrict(
            String sql,
            Integer pageSize,
            Long dataSourceId,
            String databaseName,
            String schemaName) {
        return aiToolAdapter.executeSql(sql, pageSize, dataSourceId, databaseName, schemaName, buildToolContext());
    }

    @Tool(name = "get_tables_schema", description = "Get CREATE TABLE DDL or structured schema for specific tables. Pass dataSourceId and databaseName explicitly.")
    public String getTablesSchema(
            @ToolParam(description = "Table names, for example [\"user\", \"orders\"]", required = true) List<String> tableNames,
            @ToolParam(description = "Datasource id returned by list_all_datasources.", required = true) Long dataSourceId,
            @ToolParam(description = "Target database name returned by list_all_databases.", required = true) String databaseName,
            @ToolParam(description = "Optional target schema name returned by list_all_schemas.", required = false) String schemaName) {
        return invoke(
                "get_tables_schema",
                toolContext -> aiToolAdapter.getTablesSchema(tableNames, dataSourceId, databaseName, schemaName, toolContext));
    }

    public String getTablesSchemaStrict(
            List<String> tableNames,
            Long dataSourceId,
            String databaseName,
            String schemaName) {
        return aiToolAdapter.getTablesSchema(tableNames, dataSourceId, databaseName, schemaName, buildToolContext());
    }

    @Tool(name = "text2sql", description = "Convert a natural language question into SQL using Chat2DB's internal AI. Pass datasource context explicitly when targeting a specific database.")
    public String text2sql(
            @ToolParam(description = "Natural language question, e.g. 'list top 10 users ordered by created_at desc'", required = true) String question,
            @ToolParam(description = "Optional datasource id returned by list_all_datasources.", required = false) Long dataSourceId,
            @ToolParam(description = "Optional target database name returned by list_all_databases.", required = false) String databaseName,
            @ToolParam(description = "Optional target schema name returned by list_all_schemas.", required = false) String schemaName) {
        try {
            return text2sqlStrict(question, dataSourceId, databaseName, schemaName);
        } catch (Exception e) {
            log.error("MCP tool call failed, tool=text2sql", e);
            return "MCP tool 'text2sql' failed: " + StringUtils.defaultIfBlank(e.getMessage(), "Unknown error");
        }
    }

    public String text2sqlStrict(
            String question,
            Long dataSourceId,
            String databaseName,
            String schemaName) {
        ChatRequest chatRequest = new ChatRequest();
        chatRequest.setInput(question);
        chatRequest.setDataSourceId(dataSourceId);
        chatRequest.setDatabaseName(databaseName);
        chatRequest.setSchemaName(schemaName);
        chatRequest.setQuestionType(QuestionTypeEnum.NL_2_SQL.getCode());
        chatRequest.setEnableTools(Boolean.TRUE);
        return aiChatStreamAdapter.chatSync(chatRequest);
    }

    private String invoke(String toolName, Function<ToolContext, String> action) {
        try {
            ToolContext toolContext = buildToolContext();
            return action.apply(toolContext);
        } catch (Exception e) {
            log.error("MCP tool call failed, tool={}", toolName, e);
            String message = StringUtils.defaultIfBlank(e.getMessage(), "Unknown error");
            return "MCP tool '" + toolName + "' failed: " + message;
        }
    }

    private ToolContext buildToolContext() {
        Map<String, Object> context = new LinkedHashMap<>();
        Context requestContext = ContextUtils.queryContext();
        if (requestContext != null) {
            context.put("requestContext", requestContext);
        }
        return new ToolContext(context);
    }
}

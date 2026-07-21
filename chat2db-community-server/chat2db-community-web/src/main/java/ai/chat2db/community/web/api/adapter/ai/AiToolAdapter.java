package ai.chat2db.community.web.api.adapter.ai;

import ai.chat2db.community.domain.api.exception.ai.AiToolException;
import ai.chat2db.community.domain.api.model.ai.AiToolResult;
import ai.chat2db.community.domain.api.model.request.ai.AiExecuteSqlRequest;
import ai.chat2db.community.domain.api.model.request.ai.AiGetTablesSchemaRequest;
import ai.chat2db.community.domain.api.model.request.ai.AiListTablesRequest;
import ai.chat2db.community.domain.api.model.request.ai.AiToolContextRequest;
import ai.chat2db.community.web.api.converter.ai.AiToolContextConverter;
import ai.chat2db.community.web.api.converter.ai.AiToolErrorCodeMapper;
import ai.chat2db.community.web.api.converter.ai.AiToolOutput;
import ai.chat2db.community.web.api.converter.ai.AiToolResultConverter;
import ai.chat2db.community.web.api.converter.ai.AiToolResultSerializer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

@Component
@Slf4j
public class AiToolAdapter {

    private final ai.chat2db.community.domain.api.service.ai.IAiToolService aiToolService;
    private final AiToolContextConverter aiToolContextConverter;
    private final AiToolResultConverter aiToolResultConverter;
    private final AiToolResultSerializer aiToolResultSerializer;
    private final AiToolErrorCodeMapper aiToolErrorCodeMapper;

    public AiToolAdapter(ai.chat2db.community.domain.api.service.ai.IAiToolService aiToolService,
            AiToolContextConverter aiToolContextConverter,
            AiToolResultConverter aiToolResultConverter,
            AiToolResultSerializer aiToolResultSerializer,
            AiToolErrorCodeMapper aiToolErrorCodeMapper) {
        this.aiToolService = aiToolService;
        this.aiToolContextConverter = aiToolContextConverter;
        this.aiToolResultConverter = aiToolResultConverter;
        this.aiToolResultSerializer = aiToolResultSerializer;
        this.aiToolErrorCodeMapper = aiToolErrorCodeMapper;
    }

    @Tool(name = "list_all_datasources", description = "List available Chat2DB data sources. Use this first when no datasource is selected.")
    public String listAllDataSources(ToolContext toolContext) {
        return invoke(toolContext, "list_all_datasources",
                () -> aiToolResultConverter.fromDataSources(
                        aiToolService.listAllDataSources(aiToolContextConverter.toParam(toolContext))));
    }

    @Tool(name = "list_all_tables", description = "List all tables in the connected database with comments and type.")
    public String listAllTables(
            @ToolParam(description = "Optional datasource id. Required when no datasource is selected in context.", required = false) Long dataSourceId,
            @ToolParam(description = "Optional target database name. If omitted, uses selected database context.", required = false) String databaseName,
            @ToolParam(description = "Optional target schema name. If omitted, uses selected schema context.", required = false) String schemaName,
            ToolContext toolContext) {
        return invoke(toolContext, "list_all_tables",
                () -> aiToolResultConverter.fromTables(
                        aiToolService.listAllTables(listTablesRequest(dataSourceId, databaseName, schemaName,
                                aiToolContextConverter.toParam(toolContext)))));
    }

    @Tool(name = "list_all_databases", description = "List all databases for the connected data source.")
    public String listAllDatabases(
            @ToolParam(description = "Optional datasource id. Required when no datasource is selected in context.", required = false) Long dataSourceId,
            ToolContext toolContext) {
        return invoke(toolContext, "list_all_databases",
                () -> aiToolResultConverter.fromDatabases(
                        aiToolService.listAllDatabases(dataSourceId, aiToolContextConverter.toParam(toolContext))));
    }

    @Tool(name = "list_all_schemas", description = "List all schemas in the selected database. If databaseName is empty, uses current database context.")
    public String listAllSchemas(
            @ToolParam(description = "Optional target database name", required = false) String databaseName,
            @ToolParam(description = "Optional datasource id. Required when no datasource is selected in context.", required = false) Long dataSourceId,
            ToolContext toolContext) {
        return invoke(toolContext, "list_all_schemas",
                () -> aiToolResultConverter.fromSchemas(
                        aiToolService.listAllSchemas(databaseName, dataSourceId,
                                aiToolContextConverter.toParam(toolContext))));
    }

    @Tool(name = "execute_sql", description = "Execute SQL in current database context and return concise result (rows for SELECT, update count for DML/DDL).")
    public String executeSql(
            @ToolParam(description = "SQL to execute", required = true) String sql,
            @ToolParam(description = "Optional page size for SELECT. Default 200, max 500.", required = false) Integer pageSize,
            @ToolParam(description = "Optional datasource id. Required when no datasource is selected in context.", required = false) Long dataSourceId,
            @ToolParam(description = "Optional target database name. If omitted, uses selected database context.", required = false) String databaseName,
            @ToolParam(description = "Optional target schema name. If omitted, uses selected schema context.", required = false) String schemaName,
            ToolContext toolContext) {
        return invoke(toolContext, "execute_sql",
                () -> aiToolResultConverter.fromExecuteResult(
                        aiToolService.executeSql(executeSqlRequest(sql, pageSize, dataSourceId, databaseName, schemaName,
                                aiToolContextConverter.toParam(toolContext)))));
    }

    @Tool(name = "get_tables_schema", description = "Get CREATE TABLE DDL for specific tables. Returns DDL first, then falls back to structured columns.")
    public String getTablesSchema(
            @ToolParam(description = "The table names you need, such as [\"user\", \"order\"]", required = true) List<String> tableNames,
            @ToolParam(description = "Optional datasource id. Required when no datasource is selected in context.", required = false) Long dataSourceId,
            @ToolParam(description = "Optional target database name. If omitted, uses selected database context.", required = false) String databaseName,
            @ToolParam(description = "Optional target schema name. If omitted, uses selected schema context.", required = false) String schemaName,
            ToolContext toolContext) {
        return invoke(toolContext, "get_tables_schema",
                () -> aiToolResultConverter.fromTableSchemas(
                        aiToolService.getTablesSchema(tablesSchemaRequest(tableNames, dataSourceId, databaseName, schemaName,
                                aiToolContextConverter.toParam(toolContext)))));
    }

    private AiListTablesRequest listTablesRequest(Long dataSourceId, String databaseName, String schemaName,
                                                  AiToolContextRequest toolContext) {
        AiListTablesRequest request = new AiListTablesRequest();
        request.setDataSourceId(dataSourceId);
        request.setDatabaseName(databaseName);
        request.setSchemaName(schemaName);
        request.setAiToolContextRequest(toolContext);
        return request;
    }

    private AiExecuteSqlRequest executeSqlRequest(String sql, Integer pageSize, Long dataSourceId, String databaseName,
                                                  String schemaName, AiToolContextRequest toolContext) {
        AiExecuteSqlRequest request = new AiExecuteSqlRequest();
        request.setSql(sql);
        request.setPageSize(pageSize);
        request.setDataSourceId(dataSourceId);
        request.setDatabaseName(databaseName);
        request.setSchemaName(schemaName);
        request.setAiToolContextRequest(toolContext);
        return request;
    }

    private AiGetTablesSchemaRequest tablesSchemaRequest(List<String> tableNames, Long dataSourceId,
                                                         String databaseName, String schemaName,
                                                         AiToolContextRequest toolContext) {
        AiGetTablesSchemaRequest request = new AiGetTablesSchemaRequest();
        request.setTableNames(tableNames);
        request.setDataSourceId(dataSourceId);
        request.setDatabaseName(databaseName);
        request.setSchemaName(schemaName);
        request.setAiToolContextRequest(toolContext);
        return request;
    }

    private String invoke(ToolContext toolContext, String toolName, Supplier<AiToolOutput<?>> action) {
        try {
            AiToolOutput<?> output = action.get();
            return emit(toolContext, toolName, AiToolResult.success(output.summary(), output.data()));
        } catch (AiToolException e) {
            return emit(toolContext, toolName, AiToolResult.failureWithCode(
                    aiToolErrorCodeMapper.errorCodeFor(e),
                    e.getMessage()));
        } catch (Exception e) {
            log.error("AI tool call failed, tool={}", toolName, e);
            return emit(toolContext, toolName, AiToolResult.failureWithCode(
                    "TOOL_CALL_FAILED",
                    "Tool execution failed."));
        }
    }

    private String emit(ToolContext toolContext, String toolName, AiToolResult<?> result) {
        Map<String, Object> payload = AiChatTraceSupport.payload(AiChatTraceSupport.TYPE_TOOL_RESULT);
        payload.put("name", toolName);
        payload.put("content", result.getSummary());
        AiChatTraceSupport.emit(toolContext, payload);
        return aiToolResultSerializer.toJson(result);
    }

}

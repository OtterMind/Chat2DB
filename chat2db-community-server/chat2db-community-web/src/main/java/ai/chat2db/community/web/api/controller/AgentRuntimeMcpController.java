package ai.chat2db.community.web.api.controller;

import ai.chat2db.community.domain.api.model.agent.AgentRuntimeTaskScope;
import ai.chat2db.community.domain.api.model.request.ai.AiExecuteSqlRequest;
import ai.chat2db.community.domain.api.model.request.ai.AiGetTablesSchemaRequest;
import ai.chat2db.community.domain.api.model.request.ai.AiListTablesRequest;
import ai.chat2db.community.domain.api.model.request.ai.AiToolContextRequest;
import ai.chat2db.community.domain.api.model.result.AiExecuteSqlResult;
import ai.chat2db.community.domain.api.service.agent.IAgentRuntimeDispatchService;
import ai.chat2db.community.domain.api.service.ai.IAiToolService;
import ai.chat2db.community.domain.api.service.datawiki.IDataWikiService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;

/**
 * Stateless Streamable HTTP MCP endpoint for one active external Agent Run.
 * Every request is independently authorized by the short-lived task token.
 */
@RestController
@RequestMapping("/api/agent/runtime/mcp/runs")
public class AgentRuntimeMcpController {

    static final String PROTOCOL_VERSION = "2025-06-18";
    private static final String JSON_RPC_VERSION = "2.0";
    private static final int MAX_RESOURCE_NAME_LENGTH = 512;
    private static final int MAX_SQL_LENGTH = 100_000;
    private static final int MAX_PAGE_SIZE = 500;

    private final IAgentRuntimeDispatchService dispatchService;
    private final IAiToolService aiToolService;
    private final IDataWikiService dataWikiService;
    private final ObjectMapper mapper;

    @Autowired
    public AgentRuntimeMcpController(IAgentRuntimeDispatchService dispatchService,
                                     IAiToolService aiToolService, IDataWikiService dataWikiService,
                                     ObjectMapper mapper) {
        this.dispatchService = dispatchService;
        this.aiToolService = aiToolService;
        this.dataWikiService = dataWikiService;
        this.mapper = mapper;
    }

    AgentRuntimeMcpController(IAgentRuntimeDispatchService dispatchService,
                              IAiToolService aiToolService, ObjectMapper mapper) {
        this(dispatchService, aiToolService, null, mapper);
    }

    @PostMapping(value = "/{runId}", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<JsonNode> handle(
            @PathVariable String runId,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestBody JsonNode message) {
        JsonNode id = message == null ? null : message.get("id");
        AgentRuntimeTaskScope scope;
        try {
            scope = dispatchService.authorizeTaskToken(runId, bearerToken(authorization));
        } catch (SecurityException exception) {
            return authorizationFailure(id);
        }

        return handleAuthorized(scope, message);
    }

    /** Shared JSON-RPC handling after a transport has established an immutable Agent scope. */
    ResponseEntity<JsonNode> handleAuthorized(AgentRuntimeTaskScope scope, JsonNode message) {
        JsonNode id = message == null ? null : message.get("id");

        if (message == null || !message.isObject()
                || !JSON_RPC_VERSION.equals(message.path("jsonrpc").asText())) {
            return ResponseEntity.ok(error(id, -32600, "Invalid JSON-RPC request"));
        }
        String method = StringUtils.trimToNull(message.path("method").asText(null));
        if (method == null) {
            return ResponseEntity.ok(error(id, -32600, "JSON-RPC method is required"));
        }
        if (id == null || id.isNull()) {
            if (method.startsWith("notifications/")) {
                return ResponseEntity.accepted().build();
            }
            return ResponseEntity.ok(error(null, -32600, "JSON-RPC request id is required"));
        }

        JsonNode params = message.path("params");
        return ResponseEntity.ok(switch (method) {
            case "initialize" -> response(id, initialize(params));
            case "ping" -> response(id, mapper.createObjectNode());
            case "tools/list" -> response(id, listTools());
            case "tools/call" -> response(id, callTool(scope, params));
            default -> error(id, -32601, "Method not found: " + method);
        });
    }

    ResponseEntity<JsonNode> replay(JsonNode id, String resultJson) {
        try {
            return ResponseEntity.ok(response(id, mapper.readTree(resultJson)));
        } catch (Exception exception) {
            return ResponseEntity.ok(error(id, -32603, "Stored Connector result is unavailable"));
        }
    }

    ResponseEntity<JsonNode> authorizationFailure(JsonNode id) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(error(id, -32001, "Task-scoped MCP authorization failed"));
    }

    private ObjectNode initialize(JsonNode params) {
        ObjectNode result = mapper.createObjectNode();
        result.put("protocolVersion", negotiatedProtocol(params.path("protocolVersion").asText(null)));
        ObjectNode capabilities = result.putObject("capabilities");
        capabilities.putObject("tools").put("listChanged", false);
        ObjectNode serverInfo = result.putObject("serverInfo");
        serverInfo.put("name", "chat2db-task-tools");
        serverInfo.put("version", "1.0.0");
        result.put("instructions",
                "Use only the tools and data scopes authorized for this Chat2DB Agent Run.");
        return result;
    }

    private String negotiatedProtocol(String requested) {
        return switch (StringUtils.defaultString(requested)) {
            case "2024-11-05", "2025-03-26", PROTOCOL_VERSION -> requested;
            default -> PROTOCOL_VERSION;
        };
    }

    private ObjectNode listTools() {
        ObjectNode result = mapper.createObjectNode();
        ArrayNode tools = result.putArray("tools");
        tools.add(tool("list_all_datasources",
                "List datasources within the immutable Agent Task data scope.", objectSchema()));
        tools.add(tool("list_all_databases",
                "List databases for an authorized datasource.", objectSchema(
                        property("dataSourceId", "integer", "Authorized datasource id", true))));
        tools.add(tool("list_all_schemas",
                "List schemas for an authorized datasource and database.", objectSchema(
                        property("dataSourceId", "integer", "Authorized datasource id", true),
                        property("databaseName", "string", "Target database name", true))));
        tools.add(tool("list_all_tables",
                "List tables for an authorized datasource, database and optional schema.", objectSchema(
                        property("dataSourceId", "integer", "Authorized datasource id", true),
                        property("databaseName", "string", "Target database name", true),
                        property("schemaName", "string", "Optional target schema name", false))));
        tools.add(tool("get_tables_schema",
                "Get schema details for up to 20 authorized tables.", tableSchemaInput()));
        tools.add(tool("execute_sql",
                "Execute SQL through Chat2DB scope, capability, proposal and approval controls.", objectSchema(
                        property("sql", "string", "SQL to execute", true),
                        property("pageSize", "integer", "Optional SELECT page size, maximum 500", false),
                        property("dataSourceId", "integer", "Authorized datasource id", true),
                        property("databaseName", "string", "Target database name", true),
                        property("schemaName", "string", "Optional target schema name", false))));
        tools.add(tool("list_bound_data_wikis",
                "List DataWikis bound to this Agent, including available Markdown documents.", objectSchema()));
        tools.add(tool("read_data_wiki_document",
                "Read one Markdown document from a DataWiki bound to this Agent.", objectSchema(
                        property("dataWikiId", "string", "Bound DataWiki id", true),
                        property("path", "string", "Document path returned by list_bound_data_wikis", true))));
        return result;
    }

    private ObjectNode callTool(AgentRuntimeTaskScope scope, JsonNode params) {
        String name = StringUtils.trimToNull(params.path("name").asText(null));
        JsonNode arguments = params.path("arguments");
        if (name == null || (!arguments.isMissingNode() && !arguments.isObject())) {
            return toolError("Tool name and object arguments are required");
        }
        try {
            if ("execute_sql".equals(name)) {
                return sqlToolResult(aiToolService.executeSqlResult(executeSqlRequest(scope, arguments)));
            }
            String content = switch (name) {
                case "list_all_datasources" -> aiToolService.listAllDataSources(context(scope));
                case "list_all_databases" -> aiToolService.listAllDatabases(
                        requiredLong(arguments, "dataSourceId"), context(scope));
                case "list_all_schemas" -> aiToolService.listAllSchemas(
                        requiredText(arguments, "databaseName"),
                        requiredLong(arguments, "dataSourceId"), context(scope));
                case "list_all_tables" -> aiToolService.listAllTables(
                        listTablesRequest(scope, arguments));
                case "get_tables_schema" -> aiToolService.getTablesSchema(
                        tablesSchemaRequest(scope, arguments));
                case "list_bound_data_wikis" -> listBoundDataWikis(scope);
                case "read_data_wiki_document" -> readDataWikiDocument(scope,
                        requiredText(arguments, "dataWikiId"), requiredText(arguments, "path"));
                default -> null;
            };
            if (content == null) {
                return toolError("Unknown Task-scoped MCP tool: " + name);
            }
            return toolResult(content, false);
        } catch (RuntimeException exception) {
            return toolError(StringUtils.defaultIfBlank(exception.getMessage(), "Tool execution failed"));
        }
    }

    private ObjectNode sqlToolResult(AiExecuteSqlResult value) {
        ObjectNode structured = mapper.createObjectNode();
        String kind = value.getDecision() == null ? "completed" : value.getDecision().name().toLowerCase();
        structured.put("kind", kind);
        if (StringUtils.isNotBlank(value.getApprovalId())) structured.put("approvalId", value.getApprovalId());
        if (value.getProposalVersion() != null) structured.put("proposalVersion", value.getProposalVersion());
        if (value.getRiskLevel() != null) structured.put("riskLevel", value.getRiskLevel().name().toLowerCase());
        return toolResult(value.getContent(), false, structured);
    }

    private String listBoundDataWikis(AgentRuntimeTaskScope scope) {
        ArrayNode result = mapper.createArrayNode();
        for (String wikiId : scope.getDataWikiIds() == null ? List.<String>of() : scope.getDataWikiIds()) {
            try {
                var wiki = dataWikiService.get(wikiId);
                var bundle = dataWikiService.documents(wikiId);
                ObjectNode item = result.addObject();
                item.put("id", wiki.getId());
                item.put("name", wiki.getName());
                item.put("description", StringUtils.defaultString(wiki.getDescription()));
                item.put("revision", wiki.getRevision());
                item.put("directoryPath", bundle.getRootDirectory());
                ArrayNode documents = item.putArray("documents");
                bundle.getDocuments().forEach(document -> documents.addObject()
                        .put("path", document.getPath())
                        .put("title", document.getTitle())
                        .put("kind", document.getKind()));
            } catch (RuntimeException ignored) {
                // A deleted or temporarily unavailable wiki contributes no MCP knowledge.
            }
        }
        return result.toString();
    }

    private String readDataWikiDocument(AgentRuntimeTaskScope scope, String wikiId, String path) {
        boolean bound = (scope.getDataWikiIds() == null ? List.<String>of() : scope.getDataWikiIds())
                .stream().anyMatch(wikiId::equals);
        if (!bound) {
            throw new SecurityException("DataWiki is not bound to this Agent Run");
        }
        return dataWikiService.readDocument(wikiId, path);
    }

    private AiListTablesRequest listTablesRequest(AgentRuntimeTaskScope scope, JsonNode arguments) {
        AiListTablesRequest request = new AiListTablesRequest();
        request.setDataSourceId(requiredLong(arguments, "dataSourceId"));
        request.setDatabaseName(requiredText(arguments, "databaseName"));
        request.setSchemaName(optionalText(arguments, "schemaName"));
        request.setAiToolContextRequest(context(scope));
        return request;
    }

    private AiGetTablesSchemaRequest tablesSchemaRequest(AgentRuntimeTaskScope scope, JsonNode arguments) {
        JsonNode names = arguments.path("tableNames");
        if (!names.isArray() || names.isEmpty() || names.size() > 20) {
            throw new IllegalArgumentException("tableNames must contain between 1 and 20 names");
        }
        List<String> tableNames = new ArrayList<>();
        names.forEach(name -> tableNames.add(requiredTextValue(name, "tableNames[]",
                MAX_RESOURCE_NAME_LENGTH)));
        AiGetTablesSchemaRequest request = new AiGetTablesSchemaRequest();
        request.setTableNames(List.copyOf(tableNames));
        request.setDataSourceId(requiredLong(arguments, "dataSourceId"));
        request.setDatabaseName(requiredText(arguments, "databaseName"));
        request.setSchemaName(optionalText(arguments, "schemaName"));
        request.setAiToolContextRequest(context(scope));
        return request;
    }

    private AiExecuteSqlRequest executeSqlRequest(AgentRuntimeTaskScope scope, JsonNode arguments) {
        AiExecuteSqlRequest request = new AiExecuteSqlRequest();
        request.setSql(requiredText(arguments, "sql", MAX_SQL_LENGTH));
        request.setPageSize(optionalPageSize(arguments));
        request.setDataSourceId(requiredLong(arguments, "dataSourceId"));
        request.setDatabaseName(requiredText(arguments, "databaseName"));
        request.setSchemaName(optionalText(arguments, "schemaName"));
        request.setAiToolContextRequest(context(scope));
        return request;
    }

    private AiToolContextRequest context(AgentRuntimeTaskScope scope) {
        AiToolContextRequest context = new AiToolContextRequest();
        context.setAgentRunId(scope.getRunId());
        context.setAgentToolCallId(scope.getExternalCallId());
        context.setAgentDataScopes(scope.getDataScopes() == null ? List.of() : List.copyOf(scope.getDataScopes()));
        // External Runtime calls must not keep an HTTP/MCP request open for the
        // whole human approval window. The daemon suspends the current lease
        // and resumes the same Run after the decision instead.
        context.setWaitForApprovalDecision(false);
        return context;
    }

    private ObjectNode tool(String name, String description, ObjectNode inputSchema) {
        ObjectNode tool = mapper.createObjectNode();
        tool.put("name", name);
        tool.put("description", description);
        tool.set("inputSchema", inputSchema);
        return tool;
    }

    private ObjectNode tableSchemaInput() {
        ObjectNode schema = objectSchema(
                property("dataSourceId", "integer", "Authorized datasource id", true),
                property("databaseName", "string", "Target database name", true),
                property("schemaName", "string", "Optional target schema name", false));
        ObjectNode tableNames = schema.withObject("/properties").putObject("tableNames");
        tableNames.put("type", "array");
        tableNames.putObject("items").put("type", "string");
        tableNames.put("minItems", 1);
        tableNames.put("maxItems", 20);
        schema.withArray("/required").add("tableNames");
        return schema;
    }

    private ObjectNode objectSchema(Property... properties) {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        schema.put("additionalProperties", false);
        ObjectNode fields = schema.putObject("properties");
        ArrayNode required = schema.putArray("required");
        for (Property property : properties) {
            ObjectNode field = fields.putObject(property.name());
            field.put("type", property.type());
            field.put("description", property.description());
            if (property.required()) {
                required.add(property.name());
            }
        }
        return schema;
    }

    private Property property(String name, String type, String description, boolean required) {
        return new Property(name, type, description, required);
    }

    private ObjectNode toolResult(String text, boolean error) {
        ObjectNode structured = mapper.createObjectNode();
        structured.put("kind", error ? "error" : "text");
        structured.put("text", StringUtils.defaultString(text));
        return toolResult(text, error, structured);
    }

    private ObjectNode toolResult(String text, boolean error, JsonNode structuredContent) {
        ObjectNode result = mapper.createObjectNode();
        result.put("isError", error);
        result.putArray("content").addObject().put("type", "text")
                .put("text", StringUtils.defaultString(text));
        result.set("structuredContent", structuredContent);
        return result;
    }

    private ObjectNode toolError(String message) {
        return toolResult(message, true);
    }

    private ObjectNode response(JsonNode id, JsonNode result) {
        ObjectNode response = mapper.createObjectNode();
        response.put("jsonrpc", JSON_RPC_VERSION);
        response.set("id", id.deepCopy());
        response.set("result", result);
        return response;
    }

    private ObjectNode error(JsonNode id, int code, String message) {
        ObjectNode response = mapper.createObjectNode();
        response.put("jsonrpc", JSON_RPC_VERSION);
        if (id == null || id.isNull()) response.putNull("id");
        else response.set("id", id.deepCopy());
        ObjectNode error = response.putObject("error");
        error.put("code", code);
        error.put("message", message);
        return response;
    }

    private String bearerToken(String authorization) {
        if (StringUtils.isBlank(authorization) || !authorization.startsWith("Bearer ")) {
            return null;
        }
        return StringUtils.trimToNull(authorization.substring("Bearer ".length()));
    }

    private Long requiredLong(JsonNode arguments, String name) {
        JsonNode value = arguments.path(name);
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() <= 0) {
            throw new IllegalArgumentException(name + " must be a positive integer");
        }
        return value.longValue();
    }

    private Integer optionalPageSize(JsonNode arguments) {
        JsonNode value = arguments.path("pageSize");
        if (value.isMissingNode() || value.isNull()) return null;
        if (!value.isIntegralNumber() || !value.canConvertToInt()
                || value.intValue() < 1 || value.intValue() > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("pageSize must be an integer between 1 and "
                    + MAX_PAGE_SIZE);
        }
        return value.intValue();
    }

    private String requiredText(JsonNode arguments, String name) {
        return requiredText(arguments, name, MAX_RESOURCE_NAME_LENGTH);
    }

    private String requiredText(JsonNode arguments, String name, int maxLength) {
        return requiredTextValue(arguments.path(name), name, maxLength);
    }

    private String requiredTextValue(JsonNode value, String name, int maxLength) {
        String text = value.isTextual() ? StringUtils.trimToNull(value.textValue()) : null;
        if (text == null) throw new IllegalArgumentException(name + " must be a non-empty string");
        if (text.length() > maxLength) {
            throw new IllegalArgumentException(name + " exceeds the maximum length of " + maxLength);
        }
        return text;
    }

    private String optionalText(JsonNode arguments, String name) {
        JsonNode value = arguments.path(name);
        if (value.isMissingNode() || value.isNull()) return null;
        if (!value.isTextual()) throw new IllegalArgumentException(name + " must be a string");
        String text = StringUtils.trimToNull(value.textValue());
        if (text != null && text.length() > MAX_RESOURCE_NAME_LENGTH) {
            throw new IllegalArgumentException(name + " exceeds the maximum length of "
                    + MAX_RESOURCE_NAME_LENGTH);
        }
        return text;
    }

    private record Property(String name, String type, String description, boolean required) {
    }
}

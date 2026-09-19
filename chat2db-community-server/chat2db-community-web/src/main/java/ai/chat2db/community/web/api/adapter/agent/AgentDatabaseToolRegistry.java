package ai.chat2db.community.web.api.adapter.agent;

import ai.chat2db.community.domain.api.constant.agent.AgentDatabaseConstant;
import ai.chat2db.community.domain.api.model.agent.tool.AgentToolExecutionContext;
import ai.chat2db.community.domain.api.model.request.agent.DbAgentDatabaseRequest.*;
import ai.chat2db.community.domain.api.model.request.agent.DbAgentDatabaseRequest;
import ai.chat2db.community.domain.api.model.response.agent.DbAgentDatabaseResponse;
import ai.chat2db.community.domain.api.service.agent.AgentDatabaseService;
import ai.chat2db.community.tools.exception.agent.AgentDatabaseException;
import ai.chat2db.community.tools.model.agent.runtime.AgentToolAccess;
import ai.chat2db.community.tools.model.agent.tool.AgentToolNextAction;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.type.LogicalType;
import java.util.*;
import java.util.function.BiFunction;
import java.util.function.Function;
import org.springframework.stereotype.Component;

/** V2 owns its model-facing schemas and structured results independently of V1 tools. */
@Component
public class AgentDatabaseToolRegistry {
    private final JsonMapper json = JsonMapper.builder().disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT).build();
    private final Map<String, Entry> tools = new LinkedHashMap<>();

    public AgentDatabaseToolRegistry(AgentDatabaseService service) {
        json.coercionConfigFor(LogicalType.Textual)
                .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail);
        add("db_search_datasources", "Search available connections by name. Start here when the datasource id is unknown. IDs are strings; copy an id exactly into later tools. search is a case-insensitive literal substring; omit it to browse all available connections. Results are paginated; use nextAction when present.",
                "Discover datasource ids and database types.", List.of("Never invent a datasource id. Use an id returned by db_search_datasources."),
                paged(Map.of("search", text("Case-insensitive literal connection-name substring. Filtering happens before pagination.", 256))), List.of(), Sources.class, service::listSources);
        add("db_search_databases", "Search database names for one explicit datasource id. Optional databasePattern filters names using %, _ and backslash escape; database matching is case-sensitive. Omit it to browse available databases. JDBC getCatalogs has no pattern argument, so catalog filtering occurs in V2 before pagination. Returns supportsDatabases/supportsSchemas to guide scope selection. If schemas are supported, call db_search_schemas after choosing a database; otherwise call db_search_objects. Does not use UI selection.",
                "Discover databases and scope capabilities.", List.of("Keep the same datasource id when using returned database names."),
                metadataPaged(Map.of("dataSourceId", sourceId(), "databasePattern", pattern("Match database names, e.g. sales% or %analytics%."))), List.of("dataSourceId"), Databases.class, service::listDatabases);
        add("db_search_schemas", "Search schemas for a datasource and exact database. Optional schemaPattern is passed to JDBC so unrelated schemas need not be returned; omit it to browse available schemas. database is required when supportsDatabases=true; omit it for dialects without databases. If supportsSchemas=false, an empty items list is expected; proceed to db_search_objects without schema.",
                "Discover schemas when supported by the connection.", List.of("Do not guess a schema such as public or dbo; discover it."),
                metadataPaged(Map.of("dataSourceId", sourceId(), "database", database(), "schemaPattern", pattern("Match schemas, e.g. tenant% or analytics\\_% for a literal underscore."))), List.of("dataSourceId"), Schemas.class, service::listSchemas);
        var objectFields = metadataFields();
        objectFields.put("search", text("Literal object-name substring. Use search OR objectPattern. Does not search comments.", 256));
        objectFields.put("objectPattern", pattern("Match object names, e.g. %order%. Escape underscores and percent signs to match them literally."));
        objectFields.put("types", Map.of("type", "array", "minItems", 1, "maxItems", 5, "uniqueItems", true, "default", List.of("TABLE"),
                "items", Map.of("type", "string", "enum", AgentDatabaseConstant.OBJECT_TYPES),
                "description", "Object kinds: TABLE, VIEW, FUNCTION, PROCEDURE, TRIGGER. Omitted or null defaults to TABLE only. Specify types explicitly to search other kinds."));
        add("db_search_objects", "Search database objects by name. Searches TABLE only by default; specify types explicitly for VIEW, FUNCTION, PROCEDURE or TRIGGER, or pass all five kinds to search every kind. objectPattern uses SQL wildcard matching; search is a literal substring. database/catalog is exact; schema is exact and mutually exclusive with schemaPattern. Results contain name, type, comment, database and schema. Preserve this identity for db_describe_objects. Filters and permission checks apply before stable pagination. Driver limitations or failed kind lookups appear in warnings; do not treat a partial listing as complete. No object definitions or row data are loaded into the result.",
                "Discover database objects and their exact type and scope.", List.of(
                        "Select relevant objects by name/type/comment, then call db_describe_objects with the returned type and exact scope.",
                        "Inspect warnings before claiming an object does not exist; use nextAction for remaining pages."),
                metadataPaged(objectFields), List.of("dataSourceId"), ObjectSearch.class, service::searchObjects);
        var describeFields = scopeFields();
        describeFields.put("refresh", refresh());
        var object = Map.of("type", "object", "properties", Map.of(
                "type", Map.of("type", "string", "enum", AgentDatabaseConstant.OBJECT_TYPES, "description", "Exact object kind: TABLE, VIEW, FUNCTION, PROCEDURE or TRIGGER."),
                "name", text("Exact unqualified object name within the supplied datasource/database/schema. The name is literal, including any % or _ characters.", 256)),
                "required", List.of("type", "name"), "additionalProperties", false);
        describeFields.put("objects", Map.of("type", "array", "items", object, "minItems", 1, "maxItems", 10, "uniqueItems", true,
                "description", "1 to 10 exact type/name pairs sharing the top-level dataSourceId, database and schema, e.g. [{\"type\":\"VIEW\",\"name\":\"active_users\"}]. Use separate requests for different scopes."));
        add("db_describe_objects", "Read definitions for TABLE, VIEW, FUNCTION, PROCEDURE or TRIGGER objects in an explicit datasource/database/schema scope. Object identity is the full scope plus type and name; never use UI selection. Each object returns name, type, comment and definition without duplicating columns, indexes or foreign keys. definition contains database-provided CREATE DDL, source/query body or an implementation reference, depending on the driver; it is not guaranteed to be directly executable. warnings explain unavailable definitions and permissions or driver limitations. Function/procedure/trigger support depends on the database driver.",
                "Read database object definitions.", List.of(
                        "Find objects through db_search_objects and preserve their exact datasource, database, schema and returned type.",
                        "Use exact discovered or user-supplied names for every object type. Do not invent object names.",
                        "Use column names from definition and the returned databaseType to generate dialect-correct SQL; inspect definition and warnings before treating it as executable DDL."),
                describeFields, List.of("dataSourceId", "objects"), Describe.class, service::describeObjects);
        var queryFields = scopeFields(); queryFields.put("sql", text("One SQL statement or a complete SQL batch. All-SELECT batches run automatically; any other statement requires approval of the whole batch before execution. Use ORDER BY for stable query pagination.", 32768));
        add("db_query", "Execute SQL statements in an explicit scope. A batch containing only SELECT queries runs automatically; if any statement needs approval, the entire batch waits for approval before any statement executes. Statements execute in order and stop at the first failure. Rejection or cancellation means no execution; never retry it without a new user request. Each outcome is in data.results with statementIndex, sql, success, data, page and error. Successful row results include resultId; pass that exact id to render_chart to visualize the saved data. DML/DDL outcomes include data.affectedRows when reported by the driver. page defaults to 1; pageSize defaults to 50, maximum 200. Each result has rows aligned with columns; values use database text, SQL NULL is JSON null. Large tool outputs include a bounded preview and system-managed output file references; use read or grep on the returned path to inspect more. hasMore/nextAction indicate another page; each page reruns the SQL, so results may change if data changes. Inspect schema before querying unknown tables.",
                "Query data with typed column metadata and explicit pagination.", List.of("Check ok before using data. On error follow error.field and nextAction; never treat an error as an empty result.",
                        "Use explicit column lists and a stable ORDER BY. Check each result page.hasMore and data.cellWarnings before claiming results are complete."),
                paged(queryFields), List.of("dataSourceId", "sql"), Query.class, service::query);
    }

    public List<AgentToolAccess.Tool> definitions() { return tools.values().stream().map(Entry::definition).toList(); }
    public Set<String> names() { return Collections.unmodifiableSet(tools.keySet()); }

    public DbAgentDatabaseResponse<?> execute(String name, Map<String, Object> arguments) {
        return execute(name, arguments, null);
    }

    public DbAgentDatabaseResponse<?> execute(String name, Map<String, Object> arguments, AgentToolExecutionContext context) {
        Entry tool = tools.get(name);
        if (tool == null) return DbAgentDatabaseResponse.failure("UNKNOWN_TOOL", "toolName", "Unknown V2 database tool: " + name, null);
        DbAgentDatabaseResponse<?> result;
        try { result = tool.execute.apply(arguments, context); }
        catch (AgentDatabaseException error) {
            var nextAction = error.nextAction();
            if (nextAction == null && ("schemaPattern".equals(error.field()) && arguments.get("schema") != null
                    || "search".equals(error.field()) && arguments.get("objectPattern") != null)) {
                var corrected = new LinkedHashMap<>(arguments); corrected.remove(error.field());
                nextAction = new AgentToolNextAction(name, corrected);
            }
            return DbAgentDatabaseResponse.failure(error.code(), error.field(), error.getMessage(), nextAction);
        } catch (RuntimeException error) {
            return DbAgentDatabaseResponse.failure("DATABASE_ERROR", null,
                    "Database operation failed: " + Objects.toString(error.getMessage(), error.getClass().getSimpleName()), null);
        }
        return result;
    }

    private <T> void add(String name, String description, String snippet, List<String> guidelines,
            Map<String, Object> properties, List<String> required, Class<T> type,
            Function<T, DbAgentDatabaseResponse<?>> action) {
        add(name, description, snippet, guidelines, properties, required, type, (request, context) -> action.apply(request));
    }

    private <T> void add(String name, String description, String snippet, List<String> guidelines,
            Map<String, Object> properties, List<String> required, Class<T> type,
            BiFunction<T, AgentToolExecutionContext, DbAgentDatabaseResponse<?>> action) {
        var modelProperties = new LinkedHashMap<String, Object>();
        modelProperties.put("description", descriptionField());
        properties.forEach((field, definition) -> modelProperties.put(field, required.contains(field) ? definition :
                Map.of("anyOf", List.of(definition, Map.of("type", "null")),
                        "description", Objects.toString(((Map<?, ?>) definition).get("description"), "")
                                + " Optional: omit or pass null when unused. Never use a placeholder value.")));
        var modelRequired = new ArrayList<String>();
        modelRequired.add("description");
        modelRequired.addAll(required);
        Map<String, Object> schema = Map.of("type", "object", "properties", modelProperties, "required", modelRequired, "additionalProperties", false);
        var definition = new AgentToolAccess.Tool(name, description, schema, snippet, guidelines);
        tools.put(name, new Entry(definition, (arguments, context) -> {
            T request;
            try { request = json.convertValue(arguments, type); }
            catch (IllegalArgumentException error) {
                Throwable cause = error.getCause();
                String field = cause instanceof UnrecognizedPropertyException unknown ? unknown.getPropertyName()
                        : cause instanceof JsonMappingException mapping && !mapping.getPath().isEmpty() ? mapping.getPath().get(0).getFieldName() : null;
                return DbAgentDatabaseResponse.failure("INVALID_ARGUMENT", field,
                        "Invalid argument" + (field == null ? "" : " '" + field + "'") + ". Allowed fields: " + String.join(", ", properties.keySet())
                                + ". Follow the tool schema exactly; dataSourceId is a string, page/pageSize are integers.",
                        "dataSourceId".equals(field) ? new AgentToolNextAction("db_search_datasources", Map.of()) : null);
            }
            return action.apply(request, context);
        }));
    }
    private static Map<String, Object> text(String description, int maxLength) {
        return Map.of("type", "string", "minLength", 1, "maxLength", maxLength, "description", description);
    }
    private static Map<String, Object> descriptionField() {
        return text("Briefly explain what you are doing with this tool and what the result will provide to the user.", 240);
    }
    private static Map<String, Object> pattern(String description) {
        return text(description + " JDBC patterns use % for any sequence and _ for one character; backslash escapes %, _ or backslash. Matching is case-sensitive; use names as returned by discovery tools. Omit to match all.", 256);
    }
    private static Map<String, Object> refresh() {
        return Map.of("type", "boolean", "default", false, "description", "Bypass the isolated V2 metadata cache for this lookup. Cached entries expire after 60 seconds; nextAction reuses the refreshed result.");
    }
    private static Map<String, Object> metadataPaged(Map<String, Object> fields) {
        var properties = new LinkedHashMap<>(paged(fields)); properties.put("refresh", refresh()); return properties;
    }
    private static LinkedHashMap<String, Object> metadataFields() {
        var fields = scopeFields();
        fields.put("schema", text("Exact schema name. Mutually exclusive with schemaPattern; omit both to search visible schemas.", 256));
        fields.put("schemaPattern", pattern("Match schemas, e.g. tenant% or analytics\\_%. Mutually exclusive with schema."));
        return fields;
    }
    private static Map<String, Object> sourceId() {
        return Map.of("type", "string", "pattern", "^[1-9][0-9]*$", "description", "Required datasource id string returned by db_search_datasources. Never use a connection name or UI selection.");
    }
    private static Map<String, Object> database() { return text("Exact database name returned by db_search_databases. Required when supportsDatabases=true; otherwise omit.", 256); }
    private static LinkedHashMap<String, Object> scopeFields() {
        var fields = new LinkedHashMap<String, Object>(); fields.put("dataSourceId", sourceId()); fields.put("database", database());
        fields.put("schema", text("Exact schema name from db_search_schemas. Required when supportsSchemas=true; otherwise omit.", 256));
        return fields;
    }
    private static Map<String, Object> paged(Map<String, Object> fields) {
        var properties = new LinkedHashMap<>(fields);
        properties.put("page", Map.of("type", "integer", "minimum", 1, "maximum", 1000000, "default", 1, "description", "1-based page number. Use nextAction for subsequent pages."));
        properties.put("pageSize", Map.of("type", "integer", "minimum", 1, "maximum", 200, "default", 50, "description", "Maximum number of items returned per page."));
        return properties;
    }
    private record Entry(AgentToolAccess.Tool definition, BiFunction<Map<String, Object>, AgentToolExecutionContext, DbAgentDatabaseResponse<?>> execute) { }
}

package ai.chat2db.community.web.api.adapter.agent;

import ai.chat2db.community.domain.api.constant.agent.AgentDatabaseConstant;
import ai.chat2db.community.domain.api.model.request.agent.DbAgentDatabaseRequest.*;
import ai.chat2db.community.domain.api.model.request.agent.DbAgentDatabaseRequest;
import ai.chat2db.community.domain.api.model.response.agent.DbAgentDatabaseResponse;
import ai.chat2db.community.domain.api.service.agent.AgentDatabaseService;
import ai.chat2db.community.tools.exception.agent.AgentDatabaseException;
import ai.chat2db.community.tools.model.agent.runtime.AgentToolAccess;
import ai.chat2db.community.tools.model.agent.tool.AgentToolNextAction;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.util.*;
import java.util.function.Function;
import org.springframework.stereotype.Component;

/** V2 owns its model-facing schemas and structured results independently of V1 tools. */
@Component
public class AgentDatabaseToolRegistry {
    private final JsonMapper json = JsonMapper.builder().disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .disable(com.fasterxml.jackson.databind.DeserializationFeature.ACCEPT_FLOAT_AS_INT).build();
    private final Map<String, Entry> tools = new LinkedHashMap<>();
    private static final int MAX_RESULT_BYTES = 512 * 1024;

    public AgentDatabaseToolRegistry(AgentDatabaseService service) {
        json.coercionConfigFor(com.fasterxml.jackson.databind.type.LogicalType.Textual)
                .setCoercion(com.fasterxml.jackson.databind.cfg.CoercionInputShape.Integer, com.fasterxml.jackson.databind.cfg.CoercionAction.Fail)
                .setCoercion(com.fasterxml.jackson.databind.cfg.CoercionInputShape.Float, com.fasterxml.jackson.databind.cfg.CoercionAction.Fail)
                .setCoercion(com.fasterxml.jackson.databind.cfg.CoercionInputShape.Boolean, com.fasterxml.jackson.databind.cfg.CoercionAction.Fail);
        add("db_search_datasources", "Search available connections by name. Start here when the datasource id is unknown. IDs are strings; copy an id exactly into later tools. search is a case-insensitive literal substring; omit it to browse all available connections. Results are paginated; use nextAction when present.",
                "Discover datasource ids and database types.", List.of("Never invent a datasource id. Use an id returned by db_search_datasources."),
                paged(Map.of("search", text("Case-insensitive literal connection-name substring. Filtering happens before pagination.", 256))), List.of(), Sources.class, service::listSources);
        add("db_search_databases", "Search database names for one explicit datasource id. Optional databasePattern filters names using %, _ and backslash escape; database matching is case-sensitive. Omit it to browse available databases. JDBC getCatalogs has no pattern argument, so catalog filtering occurs in V2 before pagination. Returns supportsDatabases/supportsSchemas to guide scope selection. If schemas are supported, call db_search_schemas after choosing a database; otherwise call db_search_tables. Does not use UI selection.",
                "Discover databases and scope capabilities.", List.of("Keep the same datasource id when using returned database names."),
                metadataPaged(Map.of("dataSourceId", sourceId(), "databasePattern", pattern("Match database names, e.g. sales% or %analytics%."))), List.of("dataSourceId"), Databases.class, service::listDatabases);
        add("db_search_schemas", "Search schemas for a datasource and exact database. Optional schemaPattern is passed to JDBC so unrelated schemas need not be returned; omit it to browse available schemas. database is required when supportsDatabases=true; omit it for dialects without databases. If supportsSchemas=false, an empty items list is expected; proceed to db_search_tables without schema.",
                "Discover schemas when supported by the connection.", List.of("Do not guess a schema such as public or dbo; discover it."),
                metadataPaged(Map.of("dataSourceId", sourceId(), "database", database(), "schemaPattern", pattern("Match schemas, e.g. tenant% or analytics\\_% for a literal underscore."))), List.of("dataSourceId"), Schemas.class, service::listSchemas);
        var tableFields = metadataFields();
        tableFields.put("search", text("Literal table-name substring, converted to a JDBC contains pattern. Use search OR tablePattern. Does not search comments.", 256));
        tableFields.put("tablePattern", pattern("Match table/view names, e.g. %order% or order\\_% for a literal underscore. Prefer this to listing all tables."));
        add("db_search_tables", "Search table/view names using JDBC tablePattern or a literal search substring, with optional schemaPattern. database/catalog is exact, never a pattern. schema is exact and mutually exclusive with schemaPattern; omit both to search visible schemas. Use a narrow tablePattern such as %order% before describing tables. Results include database/schema identity; preserve that exact scope for subsequent queries. Filters apply before pagination and use an isolated V2 metadata cache.",
                "Find relevant table names before inspecting columns.", List.of("Use table comments and names to select relevant tables; inspect their columns before writing SQL."),
                metadataPaged(tableFields), List.of("dataSourceId"), Tables.class, service::listTables);
        var columnFields = metadataFields();
        columnFields.put("tablePattern", pattern("Limit matching tables, e.g. order% or an exact table name with wildcard characters escaped."));
        columnFields.put("columnPattern", pattern("Find columns by name, e.g. %email% or customer\\_id. Use this before fetching full schemas across many tables."));
        add("db_search_columns", "Search column metadata with JDBC schemaPattern/tablePattern/columnPattern. Returns only matching columns with database, schema and table identity, types, nullability and comments. Use narrow patterns to locate relevant tables; then call db_describe_objects with the exact name and TABLE or VIEW type for full structure and definition. database is an exact catalog name.",
                "Find relevant columns without loading full schemas.", List.of("Prefer db_search_columns with columnPattern when the task identifies a field but not a table. Copy the returned database/schema/table into follow-up calls."),
                metadataPaged(columnFields), List.of("dataSourceId"), Columns.class, service::listColumns);
        var describeFields = scopeFields();
        describeFields.put("refresh", refresh());
        var object = Map.of("type", "object", "properties", Map.of(
                "type", Map.of("type", "string", "enum", AgentDatabaseConstant.OBJECT_TYPES, "description", "Exact object kind: TABLE, VIEW, FUNCTION, PROCEDURE or TRIGGER."),
                "name", text("Exact unqualified object name within the supplied datasource/database/schema. The name is literal, including any % or _ characters.", 256)),
                "required", List.of("type", "name"), "additionalProperties", false);
        describeFields.put("objects", Map.of("type", "array", "items", object, "minItems", 1, "maxItems", 10, "uniqueItems", true,
                "description", "1 to 10 exact type/name pairs sharing the top-level dataSourceId, database and schema, e.g. [{\"type\":\"VIEW\",\"name\":\"active_users\"}]. Use separate requests for different scopes."));
        add("db_describe_objects", "Read definitions for TABLE, VIEW, FUNCTION, PROCEDURE or TRIGGER objects in an explicit datasource/database/schema scope. Object identity is the full scope plus type and name; never use UI selection. Tables and views also return structured columns; tables include available keys/indexes. definition contains database-provided CREATE DDL, source/query body or an implementation reference, depending on the driver; it is not guaranteed to be directly executable. warnings explain unavailable metadata. Function/procedure/trigger support depends on the database driver.",
                "Read database object definitions and structured table/view schemas.", List.of(
                        "Find tables/views through db_search_tables and preserve their exact datasource, database and schema; use TABLE or VIEW as appropriate.",
                        "For functions, procedures and triggers use exact names supplied by the user or discovered with read-only catalog SQL through db_query. Do not invent object names.",
                        "Use returned column names and databaseType to generate dialect-correct SQL; inspect definition and warnings before treating it as executable DDL."),
                describeFields, List.of("dataSourceId", "objects"), Describe.class, service::describeObjects);
        var queryFields = scopeFields(); queryFields.put("sql", text("One SELECT, SHOW or DESCRIBE statement; no writes or multiple statements. Use ORDER BY for stable pagination.", 32768));
        add("db_query", "Execute one SELECT, SHOW or DESCRIBE statement in an explicit scope. Writes are not supported. page defaults to 1; pageSize defaults to 50, maximum 200. Rows are arrays aligned with columns; values use database text, SQL NULL is JSON null. No 50-row preview or cell shortening is applied. hasMore/nextAction indicate another page; each page reruns the SQL, so results may change if data changes. Inspect schema before querying unknown tables.",
                "Query data with typed column metadata and explicit pagination.", List.of("Check ok before using data. On error follow error.field and nextAction; never treat an error as an empty result.",
                        "Use explicit column lists and a stable ORDER BY. Check page.hasMore and data.cellWarnings before claiming results are complete."),
                paged(queryFields), List.of("dataSourceId", "sql"), Query.class, service::query);
    }

    public List<AgentToolAccess.Tool> definitions() { return tools.values().stream().map(Entry::definition).toList(); }
    public Set<String> names() { return Collections.unmodifiableSet(tools.keySet()); }

    public DbAgentDatabaseResponse<?> execute(String name, Map<String, Object> arguments) {
        Entry tool = tools.get(name);
        if (tool == null) return DbAgentDatabaseResponse.failure("UNKNOWN_TOOL", "toolName", "Unknown V2 database tool: " + name, null);
        DbAgentDatabaseResponse<?> result;
        try { result = tool.execute.apply(arguments); }
        catch (AgentDatabaseException error) {
            var nextAction = error.nextAction();
            if (nextAction == null && ("schemaPattern".equals(error.field()) && arguments.get("schema") != null
                    || "search".equals(error.field()) && arguments.get("tablePattern") != null)) {
                var corrected = new LinkedHashMap<>(arguments); corrected.remove(error.field());
                nextAction = new AgentToolNextAction(name, corrected);
            }
            return DbAgentDatabaseResponse.failure(error.code(), error.field(), error.getMessage(), nextAction);
        } catch (RuntimeException error) {
            return DbAgentDatabaseResponse.failure("DATABASE_ERROR", null,
                    "Database operation failed: " + Objects.toString(error.getMessage(), error.getClass().getSimpleName()), null);
        }
        try {
            if (json.writeValueAsBytes(result).length > MAX_RESULT_BYTES) {
                var retry = new LinkedHashMap<>(arguments);
                int size = retry.get("pageSize") instanceof Number number ? number.intValue() : 50;
                retry.put("pageSize", Math.max(1, size / 2));
                retry.put("page", 1);
                boolean pageable = name.equals("db_query") || name.startsWith("db_search_");
                return DbAgentDatabaseResponse.failure("RESULT_TOO_LARGE", null,
                        "Result exceeds 512 KiB. Request fewer rows/columns or describe fewer objects; for a single large value use an explicit SQL substring. No partial result was returned. Changing pageSize restarts pagination at page 1.",
                        pageable && size > 1 ? new AgentToolNextAction(name, retry) : null);
            }
        } catch (Exception error) {
            return DbAgentDatabaseResponse.failure("RESULT_ENCODING_ERROR", null, "Cannot encode the database result.", null);
        }
        return result;
    }

    private <T> void add(String name, String description, String snippet, List<String> guidelines,
            Map<String, Object> properties, List<String> required, Class<T> type,
            Function<T, DbAgentDatabaseResponse<?>> action) {
        var modelProperties = new LinkedHashMap<String, Object>();
        properties.forEach((field, definition) -> modelProperties.put(field, required.contains(field) ? definition :
                Map.of("anyOf", List.of(definition, Map.of("type", "null")),
                        "description", Objects.toString(((Map<?, ?>) definition).get("description"), "")
                                + " Optional: omit or pass null when unused. Never use a placeholder value.")));
        Map<String, Object> schema = Map.of("type", "object", "properties", modelProperties, "required", required, "additionalProperties", false);
        var definition = new AgentToolAccess.Tool(name, description, schema, snippet, guidelines);
        tools.put(name, new Entry(definition, arguments -> {
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
            return action.apply(request);
        }));
    }
    private static Map<String, Object> text(String description, int maxLength) {
        return Map.of("type", "string", "minLength", 1, "maxLength", maxLength, "description", description);
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
    private record Entry(AgentToolAccess.Tool definition, Function<Map<String, Object>, DbAgentDatabaseResponse<?>> execute) { }
}

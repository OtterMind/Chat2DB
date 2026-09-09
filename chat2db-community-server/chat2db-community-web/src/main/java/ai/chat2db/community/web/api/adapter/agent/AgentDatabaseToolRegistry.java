package ai.chat2db.community.web.api.adapter.agent;

import ai.chat2db.community.domain.api.model.agent.database.AgentDatabaseRequest.*;
import ai.chat2db.community.domain.api.model.agent.database.AgentDatabaseResult;
import ai.chat2db.community.domain.api.model.agent.database.AgentDatabaseException;
import ai.chat2db.community.domain.api.model.agent.runtime.AgentToolAccess;
import ai.chat2db.community.domain.api.service.agent.AgentDatabaseService;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.function.Function;

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
        add("db_list_datasources", "Discover available connections. Start here when the datasource id is unknown. IDs are strings; copy an id exactly into later tools. Optional search filters connection names. Results are paginated; use nextAction when present.",
                "Discover datasource ids and database types.", List.of("Never invent a datasource id. Use an id returned by db_list_datasources."),
                paged(Map.of("search", text("Filter by connection name.", 256))), List.of(), Sources.class, service::listSources);
        add("db_list_databases", "List databases for one explicit datasource id. Returns supportsDatabases/supportsSchemas to guide scope selection. If schemas are supported, call db_list_schemas after choosing a database; otherwise call db_list_tables. Does not use UI selection.",
                "Discover databases and scope capabilities.", List.of("Keep the same datasource id when using returned database names."),
                paged(Map.of("dataSourceId", sourceId())), List.of("dataSourceId"), Databases.class, service::listDatabases);
        add("db_list_schemas", "List schemas for a datasource and database. database is required when supportsDatabases=true; omit it for dialects without databases. If supportsSchemas=false, an empty items list is expected; proceed to db_list_tables without schema.",
                "Discover schemas when supported by the connection.", List.of("Do not guess a schema such as public or dbo; discover it."),
                paged(Map.of("dataSourceId", sourceId(), "database", database())), List.of("dataSourceId"), Schemas.class, service::listSchemas);
        var tableFields = scopeFields(); tableFields.put("search", text("Filter table names before describing them; do not enumerate every table in a large database.", 256));
        add("db_list_tables", "Find tables/views in an explicit datasource/database/schema scope. Supply database and schema when the dialect supports them. Returns exact names, types and comments with page information. Use search to narrow the list, then db_describe_tables for selected names.",
                "Find relevant table names before inspecting columns.", List.of("Use table comments and names to select relevant tables; inspect their columns before writing SQL."),
                paged(tableFields), List.of("dataSourceId"), Tables.class, service::listTables);
        var describeFields = scopeFields(); describeFields.put("tables", Map.of("type", "array", "items", text("Exact unqualified table name from db_list_tables.", 256), "minItems", 1, "maxItems", 10, "uniqueItems", true,
                "description", "1 to 10 exact table names in the supplied scope, e.g. [\"orders\", \"customers\"]."));
        add("db_describe_tables", "Inspect up to 10 tables. Always returns structured columns with types, nullability, keys and indexes when available; DDL and foreign keys are supplemental. warnings report unavailable metadata. Do not infer column names from the table name alone.",
                "Read structured table schemas and relationships.", List.of("Use returned column names and databaseType to generate dialect-correct SQL."),
                describeFields, List.of("dataSourceId", "tables"), Describe.class, service::describeTables);
        var queryFields = scopeFields(); queryFields.put("sql", text("One SELECT, SHOW or DESCRIBE statement; no writes or multiple statements. Use ORDER BY for stable pagination.", 32768));
        add("db_query", "Execute one SELECT, SHOW or DESCRIBE statement in an explicit scope. Writes are not supported. page defaults to 1; pageSize defaults to 50, maximum 200. Rows are arrays aligned with columns; values use database text, SQL NULL is JSON null. No 50-row preview or cell shortening is applied. hasMore/nextAction indicate another page; each page reruns the SQL, so results may change if data changes. Inspect schema before querying unknown tables.",
                "Query data with typed column metadata and explicit pagination.", List.of("Check ok before using data. On error follow error.field and nextAction; never treat an error as an empty result.",
                        "Use explicit column lists and a stable ORDER BY. Check page.hasMore and data.cellWarnings before claiming results are complete."),
                paged(queryFields), List.of("dataSourceId", "sql"), Query.class, service::query);
    }

    public List<AgentToolAccess.Tool> definitions() { return tools.values().stream().map(Entry::definition).toList(); }
    public Set<String> names() { return Collections.unmodifiableSet(tools.keySet()); }

    public AgentDatabaseResult<?> execute(String name, Map<String, Object> arguments) {
        Entry tool = tools.get(name);
        if (tool == null) return AgentDatabaseResult.failure("UNKNOWN_TOOL", "toolName", "Unknown V2 database tool: " + name, null);
        AgentDatabaseResult<?> result;
        try { result = tool.execute.apply(arguments); }
        catch (AgentDatabaseException error) {
            return AgentDatabaseResult.failure(error.code(), error.field(), error.getMessage(), error.nextAction());
        } catch (RuntimeException error) {
            return AgentDatabaseResult.failure("DATABASE_ERROR", null,
                    "Database operation failed: " + Objects.toString(error.getMessage(), error.getClass().getSimpleName()), null);
        }
        try {
            if (json.writeValueAsBytes(result).length > MAX_RESULT_BYTES) {
                var retry = new LinkedHashMap<>(arguments);
                int size = retry.get("pageSize") instanceof Number number ? number.intValue() : 50;
                retry.put("pageSize", Math.max(1, size / 2));
                retry.put("page", 1);
                boolean pageable = name.equals("db_query") || name.startsWith("db_list_");
                return AgentDatabaseResult.failure("RESULT_TOO_LARGE", null,
                        "Result exceeds 512 KiB. Request fewer rows/columns or describe fewer tables; for a single large value use an explicit SQL substring. No partial result was returned. Changing pageSize restarts pagination at page 1.",
                        pageable && size > 1 ? new AgentDatabaseResult.NextAction(name, retry) : null);
            }
        } catch (Exception error) {
            return AgentDatabaseResult.failure("RESULT_ENCODING_ERROR", null, "Cannot encode the database result.", null);
        }
        return result;
    }

    private <T> void add(String name, String description, String snippet, List<String> guidelines,
            Map<String, Object> properties, List<String> required, Class<T> type,
            Function<T, AgentDatabaseResult<?>> action) {
        Map<String, Object> schema = Map.of("type", "object", "properties", properties, "required", required, "additionalProperties", false);
        var definition = new AgentToolAccess.Tool(name, description, schema, snippet, guidelines);
        tools.put(name, new Entry(definition, arguments -> {
            T request;
            try { request = json.convertValue(arguments, type); }
            catch (IllegalArgumentException error) {
                Throwable cause = error.getCause();
                String field = cause instanceof UnrecognizedPropertyException unknown ? unknown.getPropertyName()
                        : cause instanceof JsonMappingException mapping && !mapping.getPath().isEmpty() ? mapping.getPath().get(0).getFieldName() : null;
                return AgentDatabaseResult.failure("INVALID_ARGUMENT", field,
                        "Invalid argument" + (field == null ? "" : " '" + field + "'") + ". Allowed fields: " + String.join(", ", properties.keySet())
                                + ". Follow the tool schema exactly; dataSourceId is a string, page/pageSize are integers.",
                        "dataSourceId".equals(field) ? new AgentDatabaseResult.NextAction("db_list_datasources", Map.of()) : null);
            }
            return action.apply(request);
        }));
    }
    private static Map<String, Object> text(String description, int maxLength) {
        return Map.of("type", "string", "minLength", 1, "maxLength", maxLength, "description", description);
    }
    private static Map<String, Object> sourceId() {
        return Map.of("type", "string", "pattern", "^[1-9][0-9]*$", "description", "Required datasource id string returned by db_list_datasources. Never use a connection name or UI selection.");
    }
    private static Map<String, Object> database() { return text("Exact database name returned by db_list_databases. Required when supportsDatabases=true; otherwise omit.", 256); }
    private static LinkedHashMap<String, Object> scopeFields() {
        var fields = new LinkedHashMap<String, Object>(); fields.put("dataSourceId", sourceId()); fields.put("database", database());
        fields.put("schema", text("Exact schema name from db_list_schemas. Required when supportsSchemas=true; otherwise omit.", 256));
        return fields;
    }
    private static Map<String, Object> paged(Map<String, Object> fields) {
        var properties = new LinkedHashMap<>(fields);
        properties.put("page", Map.of("type", "integer", "minimum", 1, "maximum", 1000000, "default", 1, "description", "1-based page number. Use nextAction for subsequent pages."));
        properties.put("pageSize", Map.of("type", "integer", "minimum", 1, "maximum", 200, "default", 50, "description", "Maximum number of items returned per page."));
        return properties;
    }
    private record Entry(AgentToolAccess.Tool definition, Function<Map<String, Object>, AgentDatabaseResult<?>> execute) { }
}

package ai.chat2db.community.domain.api.model.agent.database;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record AgentDatabaseResult<T>(boolean ok, Scope scope, T data, Page page, Error error,
                                      NextAction nextAction, List<String> warnings) {
    public static <T> AgentDatabaseResult<T> success(Scope scope, T data, Page page, NextAction nextAction, List<String> warnings) {
        return new AgentDatabaseResult<>(true, scope, data, page, null, nextAction, List.copyOf(warnings));
    }
    public static AgentDatabaseResult<Void> failure(String code, String field, String message, NextAction nextAction) {
        return new AgentDatabaseResult<>(false, null, null, null, new Error(code, field, message), nextAction, List.of());
    }
    public record Scope(String dataSourceId, String databaseType, String database, String schema) { }
    public record Page(int number, int size, int returned, Long total, Boolean hasMore, Integer nextPage) { }
    public record Error(String code, String field, String message) { }
    public record NextAction(String tool, Map<String, Object> arguments) { }
    public record Source(String id, String name, String type, String environment) { }
    public record Name(String name, String comment, boolean system) { }
    public record Names(List<Name> items, boolean supportsDatabases, boolean supportsSchemas) { }
    public record TableSummary(String name, String type, String comment) { }
    public record Column(String name, String type, Integer jdbcType, Boolean nullable, String defaultValue,
                         String comment, Boolean primaryKey, Boolean generated) { }
    public record Index(String name, Boolean unique, List<String> columns) { }
    public record ForeignKey(String name, String column, String referencedDatabase, String referencedSchema,
                             String referencedTable, String referencedColumn, int sequence) { }
    public record TableDetail(String name, String comment, List<Column> columns, List<Index> indexes,
                              List<ForeignKey> foreignKeys, String ddl) { }
    public record QueryColumn(String name, String type) { }
    public record CellWarning(int row, int column, String reason, Long originalCharacters, Long returnedCharacters) { }
    // Values retain their database text representation to preserve decimal precision, timestamps and SQL NULL.
    public record QueryData(List<QueryColumn> columns, List<List<String>> rows, String valueEncoding,
                            Long durationMs, List<CellWarning> cellWarnings) { }
}

package ai.chat2db.community.domain.api.model.request.agent;

import java.util.List;

public final class DbAgentDatabaseRequest {
    private DbAgentDatabaseRequest() { }
    public record Sources(String search, Integer page, Integer pageSize) { }
    public record Scope(String dataSourceId, String database, String schema) { }
    public record Databases(String dataSourceId, String databasePattern, Integer page, Integer pageSize, Boolean refresh) { }
    public record Schemas(String dataSourceId, String database, String schemaPattern, Integer page, Integer pageSize, Boolean refresh) { }
    public record ObjectSearch(String dataSourceId, String database, String schema, String search, String schemaPattern, String objectPattern, List<String> types, Integer page, Integer pageSize, Boolean refresh) {
        public Scope scope() { return new Scope(dataSourceId, database, schema); }
    }
    public record ObjectRef(String type, String name) { }
    public record Describe(String dataSourceId, String database, String schema, List<ObjectRef> objects, Boolean refresh) {
        public Scope scope() { return new Scope(dataSourceId, database, schema); }
    }
    public record Query(String dataSourceId, String database, String schema, String sql, Integer page, Integer pageSize) {
        public Scope scope() { return new Scope(dataSourceId, database, schema); }
    }
}

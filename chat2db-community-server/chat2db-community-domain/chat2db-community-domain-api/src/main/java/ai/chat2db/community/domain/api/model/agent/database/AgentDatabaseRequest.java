package ai.chat2db.community.domain.api.model.agent.database;

import java.util.List;

public final class AgentDatabaseRequest {
    private AgentDatabaseRequest() { }
    public record Sources(String search, Integer page, Integer pageSize) { }
    public record Scope(String dataSourceId, String database, String schema) { }
    public record Databases(String dataSourceId, Integer page, Integer pageSize) { }
    public record Schemas(String dataSourceId, String database, Integer page, Integer pageSize) { }
    public record Tables(String dataSourceId, String database, String schema, String search, Integer page, Integer pageSize) {
        public Scope scope() { return new Scope(dataSourceId, database, schema); }
    }
    public record Describe(String dataSourceId, String database, String schema, List<String> tables) {
        public Scope scope() { return new Scope(dataSourceId, database, schema); }
    }
    public record Query(String dataSourceId, String database, String schema, String sql, Integer page, Integer pageSize) {
        public Scope scope() { return new Scope(dataSourceId, database, schema); }
    }
}

package ai.chat2db.community.domain.api.service.agent;

import ai.chat2db.community.domain.api.model.metadata.*;
import ai.chat2db.community.domain.api.model.response.agent.DbAgentDatabaseResponse.ObjectSummary;
import java.util.List;

/** V2 metadata and its own cache in the bound connection scope. Patterns use %, _ and backslash escape. */
public interface AgentMetadataService {
    List<Database> databases(String databasePattern, boolean refresh);
    List<Schema> schemas(String database, String schemaPattern, boolean refresh);
    List<Table> tables(String database, String schemaPattern, String tablePattern, boolean refresh);
    ObjectSearchResult objects(String database, String schemaPattern, String objectPattern, List<String> types,
                               boolean supportsSchemas, boolean refresh);
    record ObjectSearchResult(List<ObjectSummary> items, List<String> warnings) { }
    Description describe(String database, String schema, String type, String name, boolean refresh);
    record Description(Table table, String definition, List<String> warnings) { }
}

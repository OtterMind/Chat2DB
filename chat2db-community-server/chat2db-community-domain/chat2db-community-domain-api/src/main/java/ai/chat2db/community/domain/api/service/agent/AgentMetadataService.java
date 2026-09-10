package ai.chat2db.community.domain.api.service.agent;

import ai.chat2db.community.domain.api.model.metadata.*;
import java.util.List;

/** V2 metadata and its own cache in the bound connection scope. Patterns use %, _ and backslash escape. */
public interface AgentMetadataService {
    List<Database> databases(String databasePattern, boolean refresh);
    List<Schema> schemas(String database, String schemaPattern, boolean refresh);
    List<Table> tables(String database, String schemaPattern, String tablePattern, boolean refresh);
    List<TableColumn> columns(String database, String schemaPattern, String tablePattern, String columnPattern, boolean refresh);
    Description describe(String database, String schema, String table, boolean refresh);
    record Description(Table table, String ddl, List<String> warnings) { }
}

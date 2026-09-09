package ai.chat2db.community.domain.api.service.agent;

import ai.chat2db.community.domain.api.model.agent.database.AgentDatabaseRequest.*;
import ai.chat2db.community.domain.api.model.agent.database.AgentDatabaseResult;
import ai.chat2db.community.domain.api.model.agent.database.AgentDatabaseResult.*;
import java.util.List;

public interface AgentDatabaseService {
    AgentDatabaseResult<List<Source>> listSources(Sources request);
    AgentDatabaseResult<Names> listDatabases(Databases request);
    AgentDatabaseResult<Names> listSchemas(Schemas request);
    AgentDatabaseResult<List<TableSummary>> listTables(Tables request);
    AgentDatabaseResult<List<TableDetail>> describeTables(Describe request);
    AgentDatabaseResult<QueryData> query(Query request);
}

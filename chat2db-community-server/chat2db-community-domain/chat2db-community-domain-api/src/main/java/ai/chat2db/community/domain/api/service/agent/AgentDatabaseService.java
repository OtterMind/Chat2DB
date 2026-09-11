package ai.chat2db.community.domain.api.service.agent;

import ai.chat2db.community.domain.api.model.request.agent.DbAgentDatabaseRequest.*;
import ai.chat2db.community.domain.api.model.response.agent.DbAgentDatabaseResponse.*;
import ai.chat2db.community.domain.api.model.response.agent.DbAgentDatabaseResponse;
import java.util.List;
import ai.chat2db.community.domain.api.model.agent.tool.AgentToolExecutionContext;

public interface AgentDatabaseService {
    DbAgentDatabaseResponse<List<Source>> listSources(Sources request);
    DbAgentDatabaseResponse<Names> listDatabases(Databases request);
    DbAgentDatabaseResponse<Names> listSchemas(Schemas request);
    DbAgentDatabaseResponse<List<TableSummary>> listTables(Tables request);
    DbAgentDatabaseResponse<List<ColumnSummary>> listColumns(Columns request);
    DbAgentDatabaseResponse<List<ObjectDetail>> describeObjects(Describe request);
    DbAgentDatabaseResponse<SqlExecutionData> query(Query request, AgentToolExecutionContext context);
}

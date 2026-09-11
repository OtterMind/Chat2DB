package ai.chat2db.community.domain.api.model.agent.chart;

import ai.chat2db.community.domain.api.model.response.agent.DbAgentDatabaseResponse.Page;
import ai.chat2db.community.domain.api.model.response.agent.DbAgentDatabaseResponse.QueryData;
import ai.chat2db.community.domain.api.model.response.agent.DbAgentDatabaseResponse.Scope;
import java.util.List;

/** Immutable data returned by one SQL statement in one Agent session. */
public record DbAgentQueryResult(String id, String sessionId, String runId, String sql,
        Scope scope, QueryData data, Page page, List<String> warnings) { }

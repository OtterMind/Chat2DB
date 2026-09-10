package ai.chat2db.community.domain.api.service.agent;

import ai.chat2db.community.domain.api.model.agent.chart.DbAgentQueryResult;

public interface IAgentQueryResultStorage {
    void create(DbAgentQueryResult result, Long userId);

    /** Returns null when the result does not exist in this user's session. */
    DbAgentQueryResult get(String sessionId, String resultId, Long userId);
}

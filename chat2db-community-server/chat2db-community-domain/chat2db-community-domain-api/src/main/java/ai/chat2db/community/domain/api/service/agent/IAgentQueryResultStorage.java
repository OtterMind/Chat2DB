package ai.chat2db.community.domain.api.service.agent;

import ai.chat2db.community.domain.api.model.agent.chart.DbAgentQueryResult;

public interface IAgentQueryResultStorage {
    void create(DbAgentQueryResult result, Long userId);

    default ai.chat2db.community.tools.model.agent.tool.AgentOutputReference output(String sessionId, String resultId, Long userId) { return null; }

    /** Returns null when the result does not exist in this user's session. */
    DbAgentQueryResult get(String sessionId, String resultId, Long userId);
}

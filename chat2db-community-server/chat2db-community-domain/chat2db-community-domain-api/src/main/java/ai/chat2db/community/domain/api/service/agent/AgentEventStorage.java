package ai.chat2db.community.domain.api.service.agent;

import ai.chat2db.community.domain.api.model.agent.AgentEvent;

import java.util.List;

public interface AgentEventStorage {

    AgentEvent append(AgentEvent event, Long userId);

    List<AgentEvent> list(String sessionId, Long userId, long afterSequence, int limit);
}

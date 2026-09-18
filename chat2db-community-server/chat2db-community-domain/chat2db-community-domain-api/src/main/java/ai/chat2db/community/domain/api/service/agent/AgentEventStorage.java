package ai.chat2db.community.domain.api.service.agent;

import ai.chat2db.community.domain.api.model.agent.AgentEvent;

import java.util.List;

public interface AgentEventStorage {

    AgentEvent append(AgentEvent event, Long userId);

    List<AgentEvent> list(String sessionId, Long userId, long afterSequence, int limit);

    /**
     * Returns the newest {@code limit} events before {@code beforeSequence} in ascending order. Implementations
     * read only those records: browsing the newest page of a long conversation must not scan the whole history.
     */
    List<AgentEvent> listBefore(String sessionId, Long userId, long beforeSequence, int limit);
}

package ai.chat2db.community.domain.api.service.agent;

import ai.chat2db.community.domain.api.enums.agent.AgentSessionStatus;
import ai.chat2db.community.domain.api.model.agent.AgentSession;
import java.util.List;

public interface AgentSessionStorage {

    AgentSession create(AgentSession session);

    AgentSession get(String sessionId, Long userId);

    List<AgentSession> listByUserId(Long userId);

    boolean compareAndSet(AgentSession session, AgentSessionStatus expectedStatus);

    AgentSession rename(String sessionId, Long userId, String title);

    void delete(String sessionId, Long userId);
}

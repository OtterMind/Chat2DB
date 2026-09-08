package ai.chat2db.community.domain.api.service.agent;

import ai.chat2db.community.domain.api.model.agent.AgentSession;
import ai.chat2db.community.domain.api.model.request.agent.AgentSessionCreateCommand;

import java.util.List;

public interface AgentService {

    AgentSession createSession(AgentSessionCreateCommand command);

    AgentSession getSession(String sessionId, Long userId);

    List<AgentSession> listSessions(Long userId);
}

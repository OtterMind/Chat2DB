package ai.chat2db.community.domain.api.service.agent;

import ai.chat2db.community.domain.api.model.agent.AgentSession;
import ai.chat2db.community.domain.api.model.agent.AgentRun;
import ai.chat2db.community.domain.api.model.request.agent.AgentRunCancelCommand;
import ai.chat2db.community.domain.api.model.request.agent.AgentRunStartCommand;
import ai.chat2db.community.domain.api.model.request.agent.AgentSessionCreateCommand;

import java.util.List;
import java.util.concurrent.CompletionStage;

public interface AgentService {

    AgentSession createSession(AgentSessionCreateCommand command);

    AgentSession getSession(String sessionId, Long userId);

    List<AgentSession> listSessions(Long userId);

    CompletionStage<AgentRun> startRun(AgentRunStartCommand command);

    CompletionStage<AgentRun> cancelRun(AgentRunCancelCommand command);
}

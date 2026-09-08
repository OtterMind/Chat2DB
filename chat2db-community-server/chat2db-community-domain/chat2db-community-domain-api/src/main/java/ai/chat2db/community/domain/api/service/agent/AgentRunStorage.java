package ai.chat2db.community.domain.api.service.agent;

import ai.chat2db.community.domain.api.model.agent.AgentRun;
import ai.chat2db.community.domain.api.model.agent.AgentRunStatus;

import java.util.List;

public interface AgentRunStorage {

    AgentRun create(AgentRun run, Long userId);

    AgentRun get(String sessionId, String runId, Long userId);

    List<AgentRun> list(String sessionId, Long userId);

    boolean compareAndSet(AgentRun run, AgentRunStatus expectedStatus, Long userId);
}

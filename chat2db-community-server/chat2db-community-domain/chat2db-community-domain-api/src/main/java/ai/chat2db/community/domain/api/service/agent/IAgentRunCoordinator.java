package ai.chat2db.community.domain.api.service.agent;

import ai.chat2db.community.domain.api.model.agent.AgentRun;
import ai.chat2db.community.domain.api.model.agent.AgentRunEvent;

import java.util.List;

public interface IAgentRunCoordinator {

    AgentRun dispatch(String runId);

    AgentRun resumeAfterApproval(String runId, String approvalContext);

    AgentRun cancel(String runId);

    List<AgentRunEvent> listEvents(String runId);
}

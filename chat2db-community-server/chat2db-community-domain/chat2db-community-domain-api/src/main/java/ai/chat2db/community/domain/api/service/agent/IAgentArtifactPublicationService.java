package ai.chat2db.community.domain.api.service.agent;

import ai.chat2db.community.domain.api.model.agent.AgentArtifactDashboardRef;
import ai.chat2db.community.domain.api.model.request.agent.AgentArtifactPublishRequest;

import java.util.List;

public interface IAgentArtifactPublicationService {
    AgentArtifactDashboardRef publishChart(AgentArtifactPublishRequest request);
    List<AgentArtifactDashboardRef> listByTask(String taskId);
    void authorizeRefresh(Long chartId, Long currentUserId);
}

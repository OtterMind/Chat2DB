package ai.chat2db.community.domain.api.service.agent;

import ai.chat2db.community.domain.api.model.agent.AgentArtifact;
import ai.chat2db.community.domain.api.model.agent.AgentArtifactDetail;
import ai.chat2db.community.domain.api.model.agent.AgentDefinition;
import ai.chat2db.community.domain.api.model.request.agent.AgentArtifactCreateRequest;
import ai.chat2db.community.domain.api.model.request.agent.AgentArtifactVersionCreateRequest;

import java.util.List;

public interface IAgentArtifactService {

    AgentArtifactDetail create(AgentArtifactCreateRequest request);

    AgentArtifactDetail addVersion(AgentArtifactVersionCreateRequest request);

    AgentArtifactDetail get(String artifactId);

    List<AgentArtifact> listByTask(String taskId);

    List<AgentArtifactDetail> extractStructuredArtifacts(String taskId, String runId,
                                                         Long createdBy, String markdown);

    boolean satisfiesOutputContract(AgentDefinition agent, String taskId);
}

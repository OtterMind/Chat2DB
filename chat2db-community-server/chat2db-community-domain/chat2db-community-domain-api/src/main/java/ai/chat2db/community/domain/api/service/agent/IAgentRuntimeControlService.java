package ai.chat2db.community.domain.api.service.agent;

import ai.chat2db.community.domain.api.model.agent.AgentRuntimeInstance;
import ai.chat2db.community.domain.api.model.agent.AgentRuntimeOption;
import ai.chat2db.community.domain.api.model.agent.AgentRuntimeProfile;
import ai.chat2db.community.domain.api.model.request.agent.AgentRuntimeHeartbeatRequest;
import ai.chat2db.community.domain.api.model.request.agent.AgentRuntimeInstanceRegisterRequest;
import ai.chat2db.community.domain.api.model.request.agent.AgentRuntimeProfileCreateRequest;
import ai.chat2db.community.domain.api.model.request.agent.AgentRuntimeProfileUpdateRequest;

import java.util.List;

public interface IAgentRuntimeControlService {

    AgentRuntimeProfile createProfile(AgentRuntimeProfileCreateRequest request);

    AgentRuntimeProfile updateProfile(AgentRuntimeProfileUpdateRequest request);

    AgentRuntimeProfile getProfile(String id);

    List<AgentRuntimeProfile> listProfiles();

    AgentRuntimeInstance register(AgentRuntimeInstanceRegisterRequest request);

    AgentRuntimeInstance heartbeat(String instanceId, AgentRuntimeHeartbeatRequest request);

    AgentRuntimeInstance getInstance(String id);

    List<AgentRuntimeInstance> listInstances();

    List<AgentRuntimeOption> listRuntimeOptions(Long ownerId);
}

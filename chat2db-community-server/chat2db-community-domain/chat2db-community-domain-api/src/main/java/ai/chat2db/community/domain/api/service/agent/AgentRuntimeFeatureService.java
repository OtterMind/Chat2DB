package ai.chat2db.community.domain.api.service.agent;

import ai.chat2db.community.domain.api.model.agent.AgentRuntimeFeatureState;
import ai.chat2db.community.domain.api.model.agent.AgentRuntimeType;
import ai.chat2db.community.domain.api.model.agent.AgentRuntimeEnableResult;
import ai.chat2db.community.domain.api.model.agent.runtime.AgentRuntimeEnvironmentRequest;

public interface AgentRuntimeFeatureService {

    AgentRuntimeType runtimeType();

    AgentRuntimeFeatureState check(AgentRuntimeEnvironmentRequest environment);

    AgentRuntimeFeatureState enable(AgentRuntimeEnvironmentRequest environment);

    default AgentRuntimeEnableResult enableAsync(AgentRuntimeEnvironmentRequest environment) {
        return new AgentRuntimeEnableResult(enable(environment), null);
    }

    AgentRuntimeFeatureState disable(AgentRuntimeEnvironmentRequest environment);
}

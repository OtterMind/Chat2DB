package ai.chat2db.community.domain.api.service.agent;

import ai.chat2db.community.domain.api.model.agent.feature.AgentRuntimeEnableResult;
import ai.chat2db.community.domain.api.model.agent.feature.AgentRuntimeFeatureState;
import ai.chat2db.community.tools.enums.agent.AgentRuntimeType;
import ai.chat2db.community.tools.model.agent.runtime.AgentRuntimeEnvironmentRequest;

public interface IAiAgentRuntimeFeatureService {

    AgentRuntimeType runtimeType();

    AgentRuntimeFeatureState check(AgentRuntimeEnvironmentRequest environment);

    AgentRuntimeFeatureState enable(AgentRuntimeEnvironmentRequest environment);

    default AgentRuntimeEnableResult enableAsync(AgentRuntimeEnvironmentRequest environment) {
        return new AgentRuntimeEnableResult(enable(environment), null);
    }

    AgentRuntimeFeatureState disable(AgentRuntimeEnvironmentRequest environment);
}

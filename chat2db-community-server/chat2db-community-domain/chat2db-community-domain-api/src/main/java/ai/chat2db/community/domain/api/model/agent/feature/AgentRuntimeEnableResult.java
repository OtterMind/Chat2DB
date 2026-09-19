package ai.chat2db.community.domain.api.model.agent.feature;

import ai.chat2db.community.tools.model.agent.runtime.AgentRuntimeEnvironmentReport;

public record AgentRuntimeEnableResult(
        AgentRuntimeFeatureState state,
        Long taskId) {

    public boolean enabled() {
        return state.enabled();
    }

    public AgentRuntimeEnvironmentReport environment() {
        return state.environment();
    }
}

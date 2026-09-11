package ai.chat2db.community.domain.api.model.agent.feature;

import ai.chat2db.community.tools.enums.agent.AgentRuntimeType;
import ai.chat2db.community.tools.model.agent.runtime.AgentRuntimeEnvironmentReport;
import java.util.Objects;

public record AgentRuntimeFeatureState(
        AgentRuntimeType runtimeType,
        boolean enabled,
        boolean installed,
        AgentRuntimeEnvironmentReport environment) {

    public AgentRuntimeFeatureState {
        Objects.requireNonNull(runtimeType, "runtimeType");
        Objects.requireNonNull(environment, "environment");
    }
}

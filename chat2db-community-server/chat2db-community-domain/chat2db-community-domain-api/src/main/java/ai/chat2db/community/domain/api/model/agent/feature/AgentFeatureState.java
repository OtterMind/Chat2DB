package ai.chat2db.community.domain.api.model.agent.feature;

import ai.chat2db.community.tools.enums.agent.AgentFeature;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record AgentFeatureState(
        AgentFeature feature,
        boolean enabled,
        boolean available,
        List<String> checks,
        Map<String, String> diagnostics) {

    public AgentFeatureState {
        Objects.requireNonNull(feature, "feature");
        checks = checks == null ? List.of() : List.copyOf(checks);
        diagnostics = diagnostics == null ? Map.of() : Map.copyOf(diagnostics);
    }
}

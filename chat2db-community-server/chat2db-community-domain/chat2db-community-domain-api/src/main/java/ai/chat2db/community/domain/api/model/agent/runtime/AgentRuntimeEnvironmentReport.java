package ai.chat2db.community.domain.api.model.agent.runtime;

import ai.chat2db.community.domain.api.model.agent.AgentRuntimeId;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record AgentRuntimeEnvironmentReport(
        AgentRuntimeId runtimeId,
        AgentRuntimeEnvironmentStatus status,
        String runtimeVersion,
        String operatingSystem,
        String architecture,
        List<String> checks,
        Map<String, String> diagnostics,
        LocalDateTime checkedAt) {

    public AgentRuntimeEnvironmentReport {
        Objects.requireNonNull(runtimeId, "runtimeId");
        Objects.requireNonNull(status, "status");
        checks = checks == null ? List.of() : List.copyOf(checks);
        diagnostics = diagnostics == null ? Map.of() : Map.copyOf(diagnostics);
        Objects.requireNonNull(checkedAt, "checkedAt");
    }

    public boolean isUsable() {
        return status != AgentRuntimeEnvironmentStatus.BLOCKED;
    }
}

package ai.chat2db.community.runtime.daemon;

import ai.chat2db.community.domain.api.enums.agent.AgentRuntimeProviderEnum;

import java.nio.file.Path;
import java.util.Map;

public record LocalRuntimeInstallation(
        AgentRuntimeProviderEnum provider,
        Path executable,
        String version,
        Map<String, String> environment) {

    public LocalRuntimeInstallation {
        environment = environment == null ? Map.of() : Map.copyOf(environment);
    }

    public LocalRuntimeInstallation(AgentRuntimeProviderEnum provider, Path executable, String version) {
        this(provider, executable, version, Map.of());
    }
}

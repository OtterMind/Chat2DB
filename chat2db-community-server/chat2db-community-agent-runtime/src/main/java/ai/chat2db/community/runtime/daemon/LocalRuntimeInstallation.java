package ai.chat2db.community.runtime.daemon;

import ai.chat2db.community.domain.api.enums.agent.AgentRuntimeProviderEnum;

import java.nio.file.Path;

public record LocalRuntimeInstallation(
        AgentRuntimeProviderEnum provider,
        Path executable,
        String version) {
}

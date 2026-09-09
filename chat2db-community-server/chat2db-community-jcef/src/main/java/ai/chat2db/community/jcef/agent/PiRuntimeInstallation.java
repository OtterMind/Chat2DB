package ai.chat2db.community.jcef.agent;

import ai.chat2db.community.domain.api.model.agent.runtime.AgentRuntimeEnvironmentRequest;

import java.io.IOException;
import java.nio.file.Path;

@FunctionalInterface
public interface PiRuntimeInstallation {

    Path install(AgentRuntimeEnvironmentRequest environment) throws IOException;
}

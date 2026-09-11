package ai.chat2db.community.tools.agent.runtime;

import ai.chat2db.community.tools.model.agent.runtime.AgentRuntimeEnvironmentRequest;
import java.io.IOException;
import java.nio.file.Path;

@FunctionalInterface
public interface IAgentRuntimeInstallation {

    Path install(AgentRuntimeEnvironmentRequest environment) throws IOException;
}

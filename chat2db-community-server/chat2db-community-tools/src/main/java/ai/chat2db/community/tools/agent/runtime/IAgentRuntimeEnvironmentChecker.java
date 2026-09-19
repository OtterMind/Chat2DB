package ai.chat2db.community.tools.agent.runtime;

import ai.chat2db.community.tools.model.agent.runtime.AgentRuntimeEnvironmentReport;
import ai.chat2db.community.tools.model.agent.runtime.AgentRuntimeEnvironmentRequest;

/** Inspects the installed runtime independently of its product enablement setting. */
@FunctionalInterface
public interface IAgentRuntimeEnvironmentChecker {
    AgentRuntimeEnvironmentReport inspect(AgentRuntimeEnvironmentRequest agentRuntimeEnvironmentRequest);
}

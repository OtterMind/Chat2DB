package ai.chat2db.community.domain.api.service.agent;

import ai.chat2db.community.domain.api.model.agent.runtime.AgentRuntimeDescriptor;
import ai.chat2db.community.domain.api.model.agent.runtime.AgentRuntimeEnvironmentReport;

/**
 * Describes one runtime available to Chat2DB V2 agent sessions.
 * Spring AI V1 sessions do not use this extension point.
 */
public interface AgentRuntimeAdapter {

    AgentRuntimeDescriptor descriptor();

    AgentRuntimeEnvironmentReport inspectEnvironment();
}

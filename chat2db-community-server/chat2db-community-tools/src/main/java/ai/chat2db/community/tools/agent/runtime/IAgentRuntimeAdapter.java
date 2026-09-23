package ai.chat2db.community.tools.agent.runtime;

import ai.chat2db.community.tools.model.agent.runtime.AgentRuntimeDescriptor;
import ai.chat2db.community.tools.model.agent.runtime.AgentRuntimeEnvironmentReport;
import ai.chat2db.community.tools.model.agent.runtime.AgentRuntimeEnvironmentRequest;
import ai.chat2db.community.tools.model.agent.runtime.AgentRuntimeSessionDeleteRequest;
import ai.chat2db.community.tools.model.agent.runtime.AgentRuntimeSessionOpenRequest;
import ai.chat2db.community.tools.model.agent.runtime.AgentRuntimeSessionResumeRequest;

/**
 * Describes one runtime available to Chat2DB V2 agent sessions.
 * Spring AI V1 sessions do not use this extension point.
 */
public interface IAgentRuntimeAdapter {

    AgentRuntimeDescriptor descriptor();

    AgentRuntimeEnvironmentReport inspectEnvironment(AgentRuntimeEnvironmentRequest request);

    IAgentRuntimeSessionHandle openSession(
            AgentRuntimeSessionOpenRequest request,
            IAgentRuntimeEventSink eventSink);

    IAgentRuntimeSessionHandle resumeSession(
            AgentRuntimeSessionResumeRequest request,
            IAgentRuntimeEventSink eventSink);

    void deleteSession(AgentRuntimeSessionDeleteRequest request);
}

package ai.chat2db.community.domain.api.service.agent;

import ai.chat2db.community.domain.api.model.agent.runtime.AgentRuntimeDescriptor;
import ai.chat2db.community.domain.api.model.agent.runtime.AgentRuntimeEnvironmentRequest;
import ai.chat2db.community.domain.api.model.agent.runtime.AgentRuntimeEnvironmentReport;
import ai.chat2db.community.domain.api.model.agent.runtime.AgentRuntimeSessionDeleteRequest;
import ai.chat2db.community.domain.api.model.agent.runtime.AgentRuntimeSessionOpenRequest;
import ai.chat2db.community.domain.api.model.agent.runtime.AgentRuntimeSessionResumeRequest;

/**
 * Describes one runtime available to Chat2DB V2 agent sessions.
 * Spring AI V1 sessions do not use this extension point.
 */
public interface AgentRuntimeAdapter {

    AgentRuntimeDescriptor descriptor();

    AgentRuntimeEnvironmentReport inspectEnvironment(AgentRuntimeEnvironmentRequest request);

    AgentRuntimeSessionHandle openSession(
            AgentRuntimeSessionOpenRequest request,
            AgentRuntimeEventSink eventSink);

    AgentRuntimeSessionHandle resumeSession(
            AgentRuntimeSessionResumeRequest request,
            AgentRuntimeEventSink eventSink);

    void deleteSession(AgentRuntimeSessionDeleteRequest request);
}

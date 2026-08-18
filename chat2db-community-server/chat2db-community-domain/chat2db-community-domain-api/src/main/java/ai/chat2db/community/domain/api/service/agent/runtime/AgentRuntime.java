package ai.chat2db.community.domain.api.service.agent.runtime;

import ai.chat2db.community.domain.api.enums.agent.AgentRuntimeTypeEnum;
import ai.chat2db.community.domain.api.model.agent.runtime.AgentRunHandle;
import ai.chat2db.community.domain.api.model.agent.runtime.AgentRuntimeCapabilities;
import ai.chat2db.community.domain.api.model.agent.runtime.AgentRuntimeResumeRequest;
import ai.chat2db.community.domain.api.model.agent.runtime.AgentRuntimeStartRequest;

public interface AgentRuntime {

    AgentRuntimeTypeEnum type();

    AgentRuntimeCapabilities capabilities();

    AgentRunHandle start(AgentRuntimeStartRequest request, AgentEventSink eventSink);

    void resume(AgentRuntimeResumeRequest request, AgentEventSink eventSink);

    void cancel(String runId);
}

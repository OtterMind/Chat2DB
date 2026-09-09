package ai.chat2db.community.jcef.agent;

import ai.chat2db.community.domain.api.service.agent.AgentRuntimeEventSink;
import ai.chat2db.community.domain.api.service.agent.AgentRuntimeSessionHandle;
import ai.chat2db.community.domain.api.model.agent.AgentModelSnapshot;

public interface PiSessionLauncher {

    AgentRuntimeSessionHandle launch(
            String sessionId,
            String externalSessionId,
            String resumeReference,
            String systemPrompt,
            AgentModelSnapshot model,
            AgentRuntimeEventSink eventSink);
}

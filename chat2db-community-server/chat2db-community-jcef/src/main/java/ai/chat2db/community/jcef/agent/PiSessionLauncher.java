package ai.chat2db.community.jcef.agent;

import ai.chat2db.community.tools.agent.runtime.IAgentRuntimeEventSink;
import ai.chat2db.community.tools.agent.runtime.IAgentRuntimeSessionHandle;
import ai.chat2db.community.tools.model.agent.runtime.AgentModelSnapshot;

public interface PiSessionLauncher {

    IAgentRuntimeSessionHandle launch(
            String sessionId,
            String externalSessionId,
            String resumeReference,
            String systemPrompt,
            AgentModelSnapshot model,
            IAgentRuntimeEventSink eventSink);
}

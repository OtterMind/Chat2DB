package ai.chat2db.community.agent.pi;

import ai.chat2db.community.tools.agent.runtime.IAgentRuntimeEventSink;
import ai.chat2db.community.tools.agent.runtime.IAgentRuntimeSessionHandle;
import ai.chat2db.community.tools.model.agent.runtime.AgentModelSnapshot;
import ai.chat2db.community.tools.model.agent.runtime.AgentRuntimeSkill;

import java.util.List;

public interface IPiSessionLauncher {

    IAgentRuntimeSessionHandle launch(
            String sessionId,
            String externalSessionId,
            String resumeReference,
            String systemPrompt,
            AgentModelSnapshot model,
            List<AgentRuntimeSkill> skills,
            IAgentRuntimeEventSink eventSink);
}

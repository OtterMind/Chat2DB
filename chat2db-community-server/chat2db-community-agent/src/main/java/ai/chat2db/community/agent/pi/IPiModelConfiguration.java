package ai.chat2db.community.agent.pi;

import ai.chat2db.community.tools.model.agent.runtime.AgentModelAccess;
import ai.chat2db.community.tools.model.agent.runtime.AgentModelSnapshot;

public interface IPiModelConfiguration extends AutoCloseable {

    AgentModelAccess prepare(AgentModelSnapshot model);

    @Override
    void close();
}

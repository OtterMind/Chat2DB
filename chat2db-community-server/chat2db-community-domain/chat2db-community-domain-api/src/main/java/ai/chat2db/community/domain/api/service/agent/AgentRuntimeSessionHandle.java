package ai.chat2db.community.domain.api.service.agent;

import ai.chat2db.community.domain.api.model.agent.runtime.AgentRuntimeCancelRequest;
import ai.chat2db.community.domain.api.model.agent.runtime.AgentRuntimeRunRef;
import ai.chat2db.community.domain.api.model.agent.runtime.AgentRuntimeRunRequest;
import ai.chat2db.community.domain.api.model.agent.runtime.AgentRuntimeSessionRef;
import ai.chat2db.community.domain.api.model.agent.runtime.AgentRuntimeSnapshot;

import java.util.concurrent.CompletionStage;

public interface AgentRuntimeSessionHandle extends AutoCloseable {

    AgentRuntimeSessionRef session();

    CompletionStage<AgentRuntimeRunRef> startRun(AgentRuntimeRunRequest request);

    CompletionStage<Void> cancel(AgentRuntimeCancelRequest request);

    CompletionStage<AgentRuntimeSnapshot> snapshot();

    @Override
    void close();
}

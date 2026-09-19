package ai.chat2db.community.tools.agent.runtime;

import ai.chat2db.community.tools.model.agent.runtime.AgentRuntimeCancelRequest;
import ai.chat2db.community.tools.model.agent.runtime.AgentRuntimeRunRef;
import ai.chat2db.community.tools.model.agent.runtime.AgentRuntimeRunRequest;
import ai.chat2db.community.tools.model.agent.runtime.AgentRuntimeSessionRef;
import ai.chat2db.community.tools.model.agent.runtime.AgentRuntimeSnapshot;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public interface IAgentRuntimeSessionHandle extends AutoCloseable {

    AgentRuntimeSessionRef session();

    CompletionStage<AgentRuntimeRunRef> startRun(AgentRuntimeRunRequest request);

    CompletionStage<Void> cancel(AgentRuntimeCancelRequest request);

    CompletionStage<AgentRuntimeSnapshot> snapshot();

    /**
     * True when the runtime resources this handle was started with changed on disk, so the next run
     * must start a fresh runtime instead of serving a process that still holds the old resources.
     */
    default boolean needsRestart() {
        return false;
    }

    /** Completes when the underlying runtime process or transport terminates. */
    default CompletionStage<Void> termination() {
        return new CompletableFuture<>();
    }

    @Override
    void close();
}

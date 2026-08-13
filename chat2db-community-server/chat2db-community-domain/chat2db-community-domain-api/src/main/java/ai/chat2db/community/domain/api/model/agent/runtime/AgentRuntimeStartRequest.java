package ai.chat2db.community.domain.api.model.agent.runtime;

import ai.chat2db.community.domain.api.model.agent.AgentDefinition;
import ai.chat2db.community.domain.api.model.agent.AgentRun;
import ai.chat2db.community.domain.api.model.agent.AgentTask;
import lombok.Data;

@Data
public class AgentRuntimeStartRequest {

    private AgentDefinition agent;

    private AgentTask task;

    private AgentRun run;

    /**
     * The user request that triggered this run. It is null for the initial
     * task-created run, where the task definition remains the active goal.
     */
    private String currentInput;

    /**
     * Context assembled by the Chat2DB control plane. Runtimes must not load
     * unrestricted task or datasource state on their own.
     */
    private String assembledContext;
}

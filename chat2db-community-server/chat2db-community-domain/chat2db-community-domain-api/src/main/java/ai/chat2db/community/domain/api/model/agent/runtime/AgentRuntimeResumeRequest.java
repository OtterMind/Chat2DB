package ai.chat2db.community.domain.api.model.agent.runtime;

import lombok.Data;

@Data
public class AgentRuntimeResumeRequest {

    private String runId;

    private String runtimeExecutionId;

    private String resumeToken;

    private String payload;

    /**
     * Current control-plane snapshot for runtimes that resume with a fresh
     * model turn instead of restoring a provider-native session.
     */
    private AgentRuntimeStartRequest startRequest;
}

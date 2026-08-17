package ai.chat2db.community.runtime.provider;

import ai.chat2db.community.domain.api.model.agent.AgentRuntimeProfile;
import ai.chat2db.community.domain.api.model.agent.runtime.AgentRuntimeStartRequest;
import lombok.Data;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Data
public class ProviderExecutionRequest {

    private String runId;
    private int leaseAttempt;
    private AgentRuntimeProfile runtimeProfile;
    private AgentRuntimeStartRequest startRequest;
    private Path workingDirectory;
    private String resumeSessionId;
    private ProviderApprovalHandler approvalHandler;
    private List<ProviderMcpEndpoint> mcpEndpoints = new ArrayList<>();

    /**
     * Already-resolved, task-safe process environment. Profile environment
     * references must be resolved by the Daemon without exposing their values
     * to the Chat2DB control plane or logs.
     */
    private Map<String, String> environment = new LinkedHashMap<>();
}

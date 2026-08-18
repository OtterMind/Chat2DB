package ai.chat2db.community.domain.api.model.agent.runtime;

import lombok.Data;

@Data
public class AgentRuntimeCapabilities {

    private boolean streaming;

    private boolean toolCalling;

    private boolean cancellation;

    private boolean approvalResume;

    private boolean sessionResume;

    private boolean externalProcess;

    private String runtimeVersion;
}

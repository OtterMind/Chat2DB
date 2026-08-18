package ai.chat2db.community.domain.api.model.agent;

import ai.chat2db.community.domain.api.enums.agent.AgentRuntimeInstanceStatusEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentRuntimeProviderEnum;
import lombok.Data;

@Data
public class AgentRuntimeOption {

    private String profileId;
    private String profileName;
    private AgentRuntimeProviderEnum provider;
    private String executable;
    private Boolean defaultProfile;
    private Boolean installed;
    private Boolean online;
    private AgentRuntimeInstanceStatusEnum status;
    private String providerVersion;
    private String daemonId;
    private Integer activeRuns;
    private Integer maxConcurrency;
}

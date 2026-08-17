package ai.chat2db.community.domain.api.model.agent;

import ai.chat2db.community.domain.api.enums.agent.AgentRuntimeInstanceStatusEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentRuntimeProviderEnum;
import lombok.Data;

import java.util.Date;
import java.util.LinkedHashSet;
import java.util.Set;

@Data
public class AgentRuntimeInstance {

    private String id;
    private String daemonId;
    private AgentRuntimeProviderEnum provider;
    private String providerVersion;
    private String protocolVersion;
    private Set<String> capabilities = new LinkedHashSet<>();
    private Integer maxConcurrency;
    private Integer activeRuns;
    private AgentRuntimeInstanceStatusEnum status;
    private Date lastHeartbeatAt;
    private Date registeredAt;
    private Date gmtModified;
    private Long revision;
}

package ai.chat2db.community.domain.api.model.request.agent;

import ai.chat2db.community.domain.api.enums.agent.AgentRuntimeProviderEnum;
import lombok.Data;

import java.util.LinkedHashSet;
import java.util.Set;

@Data
public class AgentRuntimeInstanceRegisterRequest {

    private String daemonId;
    private AgentRuntimeProviderEnum provider;
    private String providerVersion;
    private String protocolVersion;
    private Set<String> capabilities = new LinkedHashSet<>();
    private Integer maxConcurrency;
}

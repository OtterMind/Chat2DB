package ai.chat2db.community.domain.api.model.agent;

import ai.chat2db.community.domain.api.enums.agent.AgentRuntimeProviderEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentRuntimeTransportEnum;
import lombok.Data;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
public class AgentRuntimeProfile {

    private String id;
    private String name;
    private AgentRuntimeTransportEnum transport;
    private AgentRuntimeProviderEnum provider;
    private String executable;
    private String model;
    private String workingDirectoryPolicy;
    private List<String> customArguments = new ArrayList<>();
    private Map<String, String> environmentReferences = new LinkedHashMap<>();
    private String mcpConfiguration;
    private Integer timeoutSeconds;
    private Integer maxConcurrency;
    private String thinkingMode;
    private String serviceTier;
    private Boolean sessionResumeEnabled;
    private Boolean approvalBridgeEnabled;
    private Boolean enabled;
    private Long createdBy;
    private Date gmtCreate;
    private Date gmtModified;
    private Long revision;
}

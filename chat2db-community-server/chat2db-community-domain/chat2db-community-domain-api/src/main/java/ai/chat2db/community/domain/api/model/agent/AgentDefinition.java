package ai.chat2db.community.domain.api.model.agent;

import ai.chat2db.community.domain.api.enums.agent.AgentCapabilityEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentRuntimeTypeEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentStatusEnum;
import lombok.Data;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Data
public class AgentDefinition {

    private String id;

    private String name;

    private String avatar;

    private String description;

    private AgentStatusEnum status;

    private AgentRuntimeTypeEnum runtimeType;

    private String runtimeProfileId;

    private String modelConfigId;

    private String systemPrompt;

    private Set<AgentCapabilityEnum> capabilities = new LinkedHashSet<>();

    private List<AgentDataScope> dataScopes = new ArrayList<>();

    private String outputContract;

    private Long createdBy;

    private Date gmtCreate;

    private Date gmtModified;

    private Long revision;
}

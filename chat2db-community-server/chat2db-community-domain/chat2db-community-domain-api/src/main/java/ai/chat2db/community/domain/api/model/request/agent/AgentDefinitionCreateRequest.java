package ai.chat2db.community.domain.api.model.request.agent;

import ai.chat2db.community.domain.api.enums.agent.AgentCapabilityEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentRuntimeTypeEnum;
import ai.chat2db.community.domain.api.model.agent.AgentDataScope;
import ai.chat2db.community.domain.api.model.agent.AgentDataWikiBinding;
import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Data
public class AgentDefinitionCreateRequest {

    private String name;

    private String avatar;

    private String description;

    private AgentRuntimeTypeEnum runtimeType;

    private String runtimeProfileId;

    private String modelConfigId;

    private String systemPrompt;

    private Set<AgentCapabilityEnum> capabilities = new LinkedHashSet<>();

    private List<AgentDataScope> dataScopes = new ArrayList<>();

    private List<String> dataWikiIds = new ArrayList<>();
    private List<AgentDataWikiBinding> dataWikiBindings = new ArrayList<>();

    private String outputContract;

    private Long createdBy;
}

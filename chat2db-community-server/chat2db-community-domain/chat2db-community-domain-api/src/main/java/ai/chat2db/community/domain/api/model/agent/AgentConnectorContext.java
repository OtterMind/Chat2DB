package ai.chat2db.community.domain.api.model.agent;

import ai.chat2db.community.domain.api.enums.agent.AgentCapabilityEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentConnectorSessionStatusEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentConnectorExecutionModeEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentRuntimeTypeEnum;
import lombok.Data;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Current Agent identity and effective authorization visible to one Connector Session.
 */
@Data
public class AgentConnectorContext {

    private String sessionId;

    private AgentConnectorSessionStatusEnum sessionStatus;

    private Date lastUsedAt;

    private Date refreshTokenExpiresAt;

    private String agentId;

    private String agentName;

    private String agentAvatar;

    private AgentConnectorExecutionModeEnum executionMode;

    private String externalRuntimeName;

    private AgentRuntimeTypeEnum runtimeType;

    private String runtimeProfileId;

    private Set<AgentCapabilityEnum> capabilities = new LinkedHashSet<>();

    private List<AgentDataScope> explicitDataScopes = new ArrayList<>();

    private List<AgentDataScope> effectiveDataScopes = new ArrayList<>();

    private List<AgentConnectorDataWikiContext> dataWikis = new ArrayList<>();
}

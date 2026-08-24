package ai.chat2db.community.domain.api.model.agent;

import ai.chat2db.community.domain.api.enums.agent.AgentApprovalModeEnum;
import lombok.Data;

/**
 * Agent-specific execution policy applied to every table contributed by one DataWiki.
 */
@Data
public class AgentDataWikiBinding {

    private String dataWikiId;

    private Integer maxRows = 200;

    private Integer timeoutSeconds = 60;

    private AgentApprovalModeEnum approvalMode = AgentApprovalModeEnum.RISK_BASED;

    private Boolean allowProduction = false;
}

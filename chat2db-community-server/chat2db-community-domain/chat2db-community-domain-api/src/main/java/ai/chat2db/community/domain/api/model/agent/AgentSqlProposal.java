package ai.chat2db.community.domain.api.model.agent;

import ai.chat2db.community.domain.api.enums.agent.AgentRiskLevelEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentSqlOperationClassEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentSqlProposalStatusEnum;
import lombok.Data;

import java.util.Date;

@Data
public class AgentSqlProposal {
    private String id;
    private String runId;
    private Integer proposalVersion;
    private String sqlSnapshot;
    private String sqlHash;
    private Long dataSourceId;
    private String databaseName;
    private String schemaName;
    private AgentSqlOperationClassEnum operationClass;
    private AgentRiskLevelEnum riskLevel;
    private String estimatedImpact;
    private AgentSqlProposalStatusEnum status;
    private Date createdAt;
    private Date updatedAt;
    private Long revision;
}

package ai.chat2db.community.domain.api.model.agent;

import ai.chat2db.community.domain.api.enums.agent.AgentApprovalModeEnum;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class AgentDataScope {

    private Long dataSourceId;

    private String databaseName;

    private String schemaName;

    private List<String> tableNames = new ArrayList<>();

    private List<String> excludedTableNames = new ArrayList<>();

    private Integer maxRows = 200;

    private Integer timeoutSeconds = 60;

    private AgentApprovalModeEnum approvalMode = AgentApprovalModeEnum.RISK_BASED;

    private Boolean allowProduction = false;
}

package ai.chat2db.community.domain.api.model.request.agent;

import lombok.Data;

@Data
public class AgentSqlToolRequest {
    private String runId;
    private String toolCallId;
    private String sql;
    private Long dataSourceId;
    private String databaseName;
    private String schemaName;
}

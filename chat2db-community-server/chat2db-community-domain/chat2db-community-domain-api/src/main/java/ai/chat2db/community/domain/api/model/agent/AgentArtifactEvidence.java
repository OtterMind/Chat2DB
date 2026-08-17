package ai.chat2db.community.domain.api.model.agent;

import lombok.Data;

import java.util.Date;

@Data
public class AgentArtifactEvidence {

    private String id;
    private String artifactId;
    private Integer artifactVersion;
    private String runId;
    private String toolAttemptId;
    private Long dataSourceId;
    private String databaseName;
    private String schemaName;
    private String sqlSnapshot;
    private String sqlHash;
    private Date executedAt;
    private Long rowCount;
    private String resultSnapshotId;
    private Date createdAt;
    private Boolean valid;
    private String invalidReason;
}

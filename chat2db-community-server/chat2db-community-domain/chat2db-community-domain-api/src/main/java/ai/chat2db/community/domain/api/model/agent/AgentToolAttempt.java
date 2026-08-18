package ai.chat2db.community.domain.api.model.agent;

import ai.chat2db.community.domain.api.enums.agent.AgentToolAttemptStatusEnum;
import lombok.Data;

import java.util.Date;

@Data
public class AgentToolAttempt {
    private String id;
    private String runId;
    private String proposalId;
    private Integer proposalVersion;
    private String toolCallId;
    private String toolName;
    private AgentToolAttemptStatusEnum status;
    private Boolean writeOperation;
    private String resultContent;
    private String errorMessage;
    private Date preparedAt;
    private Date executingAt;
    private Date completedAt;
    private Long revision;
}

package ai.chat2db.community.domain.api.model.agent.runtime;

import lombok.Data;

import java.util.Date;

@Data
public class AgentRunHandle {

    private String runId;

    private String runtimeExecutionId;

    private Date acceptedAt;
}

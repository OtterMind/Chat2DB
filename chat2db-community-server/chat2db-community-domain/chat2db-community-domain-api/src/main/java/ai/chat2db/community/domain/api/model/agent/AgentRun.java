package ai.chat2db.community.domain.api.model.agent;

import ai.chat2db.community.domain.api.enums.agent.AgentRunStatusEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentRunTriggerTypeEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentRuntimeTypeEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentRuntimeProviderEnum;
import lombok.Data;

import java.util.Date;

@Data
public class AgentRun {

    private String id;

    private String taskId;

    private String agentId;

    private AgentRuntimeTypeEnum runtimeType;

    private String runtimeProfileId;

    private AgentRuntimeProviderEnum runtimeProvider;

    private String runtimeProfileSnapshot;

    /** Provider-owned resumable session identifier, for example a Codex Thread ID. */
    private String providerSessionId;

    private AgentRunTriggerTypeEnum triggerType;

    private AgentRunStatusEnum status;

    private Integer attempt;

    private String parentRunId;

    private Date gmtCreate;

    private Date gmtModified;

    private Date startedAt;

    private Date completedAt;

    private String failureReason;

    private String resultSummary;

    private Long revision;
}

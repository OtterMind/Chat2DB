package ai.chat2db.community.domain.api.model.agent;

import ai.chat2db.community.domain.api.enums.agent.AgentTaskLinkStateEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentRunStatusEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentTaskStatusEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentTaskScheduleExecutionSourceEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentTaskScheduleExecutionStatusEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentTaskScheduleReasonCodeEnum;
import com.alibaba.fastjson2.annotation.JSONField;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

import java.util.Date;

@Data
public class AgentTaskScheduleExecution {
    private String id;
    private String scheduleId;
    private AgentTaskScheduleExecutionSourceEnum source;
    private Date plannedAt;
    private AgentTaskScheduleExecutionStatusEnum status;
    private String taskId;
    private String runId;
    private Integer attempt;
    @JsonIgnore
    @JSONField(serialize = false, deserialize = false)
    private String leaseToken;
    private Date leaseExpiresAt;
    private AgentTaskScheduleReasonCodeEnum reasonCode;
    private String failureReason;
    private AgentTaskLinkStateEnum taskLinkState;
    private AgentTaskStatusEnum taskStatus;
    private AgentRunStatusEnum runStatus;
    private String runFailureReason;
    private String resultSummary;
    private Date gmtCreate;
    private Date gmtModified;
    private Long revision;
}

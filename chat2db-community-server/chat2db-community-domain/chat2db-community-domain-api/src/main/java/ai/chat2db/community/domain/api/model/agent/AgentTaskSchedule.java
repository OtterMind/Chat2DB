package ai.chat2db.community.domain.api.model.agent;

import ai.chat2db.community.domain.api.enums.agent.AgentTaskScheduleCatchUpPolicyEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentTaskScheduleConcurrencyPolicyEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentTaskScheduleStatusEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentTaskScheduleTypeEnum;
import lombok.Data;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

@Data
public class AgentTaskSchedule {
    private String id;
    private String name;
    private String taskTitle;
    private String taskDescription;
    private String acceptanceCriteria;
    private String assigneeAgentId;
    private Integer priority;
    private List<AgentDataScope> dataScopeSnapshot = new ArrayList<>();
    private AgentTaskScheduleTypeEnum scheduleType;
    private Date scheduledAt;
    private String cronExpression;
    private String timezone;
    private AgentTaskScheduleStatusEnum status;
    private AgentTaskScheduleConcurrencyPolicyEnum concurrencyPolicy;
    private AgentTaskScheduleCatchUpPolicyEnum catchUpPolicy;
    private Date nextRunAt;
    private Date lastRunAt;
    private Long createdBy;
    private Date gmtCreate;
    private Date gmtModified;
    private Long revision;
}

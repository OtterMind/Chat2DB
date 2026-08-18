package ai.chat2db.community.domain.api.model.request.agent;

import ai.chat2db.community.domain.api.enums.agent.AgentTaskScheduleTypeEnum;
import ai.chat2db.community.domain.api.model.agent.AgentDataScope;
import lombok.Data;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

@Data
public class AgentTaskScheduleCreateRequest {
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
    private Long createdBy;
}

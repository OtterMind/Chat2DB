package ai.chat2db.community.domain.api.model.agent;

import ai.chat2db.community.domain.api.enums.agent.AgentTaskOriginTypeEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentTaskStatusEnum;
import lombok.Data;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

@Data
public class AgentTask {

    private String id;

    private String title;

    private String description;

    private String acceptanceCriteria;

    private AgentTaskStatusEnum status;

    private Integer priority;

    private String assigneeAgentId;

    private Long createdBy;

    private AgentTaskOriginTypeEnum originType;

    private String originSessionId;

    private String originMessageId;

    private List<AgentDataScope> dataScopeSnapshot = new ArrayList<>();

    private Date dataScopeSyncedAt;

    private Long dataScopeSyncedFromAgentRevision;

    private String currentRunId;

    private Date gmtCreate;

    private Date gmtModified;

    private Date completedAt;

    private Date archivedAt;

    private Long revision;
}

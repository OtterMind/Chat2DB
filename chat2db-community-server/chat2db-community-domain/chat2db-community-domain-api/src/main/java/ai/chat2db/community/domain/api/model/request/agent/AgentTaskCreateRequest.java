package ai.chat2db.community.domain.api.model.request.agent;

import ai.chat2db.community.domain.api.enums.agent.AgentTaskOriginTypeEnum;
import ai.chat2db.community.domain.api.model.agent.AgentDataScope;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class AgentTaskCreateRequest {

    private String title;

    private String description;

    private String acceptanceCriteria;

    private Integer priority;

    private String assigneeAgentId;

    private Long createdBy;

    private AgentTaskOriginTypeEnum originType;

    private String originSessionId;

    private String originMessageId;

    private List<AgentDataScope> dataScopeSnapshot = new ArrayList<>();
}

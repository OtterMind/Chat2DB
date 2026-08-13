package ai.chat2db.community.domain.api.model.request.agent;

import ai.chat2db.community.domain.api.enums.agent.AgentTaskContextTypeEnum;
import lombok.Data;

@Data
public class AgentTaskContextCreateRequest {

    private String taskId;

    private AgentTaskContextTypeEnum type;

    private String title;

    private String content;

    private String attachmentName;

    private String attachmentMimeType;

    private Long attachmentSize;

    private Long createdBy;
}

package ai.chat2db.community.domain.api.model.agent;

import ai.chat2db.community.domain.api.enums.agent.AgentTaskContextTypeEnum;
import lombok.Data;

import java.util.Date;

@Data
public class AgentTaskContext {

    private String id;

    private String taskId;

    private AgentTaskContextTypeEnum type;

    private String title;

    private String content;

    private String attachmentName;

    private String attachmentMimeType;

    private Long attachmentSize;

    private Long createdBy;

    private Date createdAt;
}

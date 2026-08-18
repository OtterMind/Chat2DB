package ai.chat2db.community.web.api.model.request.agent;

import lombok.Data;

@Data
public class AgentTaskScheduleCronPreviewRequest {

    private String expression;

    private String timezone = "UTC";
}

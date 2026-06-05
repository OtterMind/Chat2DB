package ai.chat2db.server.web.api.controller.ai.conversation.request;

import ai.chat2db.server.tools.base.wrapper.request.PageQueryRequest;
import lombok.Data;

@Data
public class AiConversationQueryRequest extends PageQueryRequest {

    private String searchKey;
    private Long dataSourceId;
    private String status;
}

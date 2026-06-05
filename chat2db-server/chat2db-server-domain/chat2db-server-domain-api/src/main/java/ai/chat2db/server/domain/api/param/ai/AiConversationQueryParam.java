package ai.chat2db.server.domain.api.param.ai;

import ai.chat2db.server.tools.base.wrapper.param.PageQueryParam;
import lombok.Data;

/**
 * AI 会话分页查询
 */
@Data
public class AiConversationQueryParam extends PageQueryParam {

    private String searchKey;
    private Long dataSourceId;
    private Long userId;
    private String status;
}

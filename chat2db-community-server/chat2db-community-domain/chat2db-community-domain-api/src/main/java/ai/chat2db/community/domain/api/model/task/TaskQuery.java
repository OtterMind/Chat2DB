package ai.chat2db.community.domain.api.model.task;

import ai.chat2db.community.tools.wrapper.param.PageQueryParam;
import lombok.Data;

@Data
public class TaskQuery extends PageQueryParam {

    private String status;

    private Long userId;

    private Long organizationId;
}

package ai.chat2db.community.domain.api.model.task;

import ai.chat2db.community.tools.wrapper.param.PageQueryParam;
import lombok.Data;

@Data
public class TaskQuery extends PageQueryParam {

    private String status;

    private Long userId;

    private Long organizationId;

    /**
     * Cursor for the task center: return terminal or active tasks older than this task id, newest
     * first. When set, pageNo is ignored and the response reports pageNo 1 with total 0; the
     * frontend derives the next cursor from the last returned task.
     */
    private Long beforeId;
}

package ai.chat2db.community.web.api.model.request.task;

import ai.chat2db.community.domain.api.model.task.TaskConstants;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class TaskEventQueryRequest {

    @NotNull
    private Long taskId;

    private Long afterSequence;

    private Long beforeSequence;

    private Integer limit = TaskConstants.DEFAULT_EVENT_LIMIT;

    public int effectiveLimit() {
        return limit == null ? TaskConstants.DEFAULT_EVENT_LIMIT : limit;
    }
}

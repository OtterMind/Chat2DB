package ai.chat2db.community.web.api.model.request.task;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class TaskIdRequest {

    @NotNull
    private Long taskId;
}

package ai.chat2db.community.domain.api.model.request.task;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class TaskRecordStopRequest {

    @NotNull
    private Long id;
}

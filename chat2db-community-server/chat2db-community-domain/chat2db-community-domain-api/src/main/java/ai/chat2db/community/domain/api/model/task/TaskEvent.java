package ai.chat2db.community.domain.api.model.task;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TaskEvent {

    private Long eventId;

    private Long taskId;

    private Long sequence;

    private String level;

    private String code;

    private String stage;

    private String message;

    private Map<String, Object> details;

    private Date createdAt;
}

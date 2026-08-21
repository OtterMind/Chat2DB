package ai.chat2db.community.domain.api.model.task;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TaskStatusPatch {

    private Integer progress;

    private String stage;

    private String progressMessage;

    private String errorCode;

    private String errorMessage;

    private String artifactId;

    private Date startedAt;

    private Date finishedAt;

    private Date updatedAt;
}

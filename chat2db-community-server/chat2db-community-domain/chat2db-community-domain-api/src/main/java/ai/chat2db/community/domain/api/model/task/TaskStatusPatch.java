package ai.chat2db.community.domain.api.model.task;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;
import java.util.List;

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

    /**
     * Every artifact published by the transition, primary first. {@code null} leaves the stored
     * artifact set untouched; when present, its first element also fills {@link #artifactId}.
     */
    private List<String> artifactIds;

    private Date startedAt;

    private Date finishedAt;

    private Date updatedAt;
}

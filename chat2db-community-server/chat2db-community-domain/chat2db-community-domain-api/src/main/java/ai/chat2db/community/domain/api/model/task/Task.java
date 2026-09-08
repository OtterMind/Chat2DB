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
public class Task {

    private Long id;

    private String type;

    private String name;

    private String status;

    private Integer progress;

    private String stage;

    private String progressMessage;

    private TaskTargetSnapshot target;

    /**
     * Serialized {@code TaskSpec} captured at submission; the resume path deserializes it to
     * resubmit the task without the original request.
     */
    private String specJson;

    private String errorCode;

    private String errorMessage;

    private String artifactId;

    /**
     * All published outputs of the task, including the primary one named by {@link #artifactId}.
     * Filled by the storage read paths; never carried into a status patch.
     */
    private List<TaskArtifact> artifacts;

    /**
     * Carrier for {@code FileTaskStorage}, which keeps checkpoints inside the task snapshot.
     * {@code H2TaskStorage} stores them in a dedicated table and never fills this field; read them
     * through {@code TaskStorage.listResumeStates}.
     */
    private List<ResumeState> resumeStates;

    private Long userId;

    private Long organizationId;

    private Date createdAt;

    private Date startedAt;

    private Date finishedAt;

    private Date updatedAt;
}

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
public class Task {

    private Long id;

    private String type;

    private String name;

    private String status;

    private Integer progress;

    private String stage;

    private String progressMessage;

    /** Source rows processed so far; null for tasks that do not report row counts. */
    private Long processedRows;

    /** Source rows expected in total; null when the task cannot know the total. */
    private Long totalRows;

    /** Measured throughput of the current run. */
    private Double rowsPerSecond;

    /** Estimated seconds remaining; null when the total or rate is unknown. */
    private Long etaSeconds;

    private TaskTargetSnapshot target;

    private String errorCode;

    private String errorMessage;

    private String artifactId;

    /**
     * Raw import/export parameter snapshot, kept so a finished task can seed a new task with the same
     * configuration. Optional: legacy records and tasks created without a spec leave it {@code null}.
     */
    private String specJson;

    /**
     * Import resume checkpoint ({@code processedRows}, {@code targetTable}, {@code conflictPolicy}).
     * Import tasks only; export tasks and legacy records leave it {@code null}.
     */
    private String checkpointJson;

    /**
     * Absolute path of the secondary error artifact listing rejected rows, as returned by
     * {@code ArtifactService#publish}. The primary artifact stays in {@link #artifactId}; {@code null}
     * when the task produced no error file.
     */
    private String errorArtifactId;

    private Long userId;

    private Long organizationId;

    private Date createdAt;

    private Date startedAt;

    private Date finishedAt;

    private Date updatedAt;
}

package ai.chat2db.community.domain.api.model.task;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TaskProgress {

    private Integer progress;

    private String stage;

    private String message;

    /** Source rows processed so far; null when the task does not report row counts. */
    private Long processedRows;

    /** Source rows expected in total; null when the format or options cannot know it. */
    private Long totalRows;

    /** Measured throughput; null before enough time has elapsed or for non-row tasks. */
    private Double rowsPerSecond;

    /** Estimated seconds remaining; null when the total or rate is unknown. */
    private Long etaSeconds;
}

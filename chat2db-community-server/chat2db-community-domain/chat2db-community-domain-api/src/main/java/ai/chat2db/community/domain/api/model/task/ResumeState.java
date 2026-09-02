package ai.chat2db.community.domain.api.model.task;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

/**
 * Persisted progress of one task shard, kept so an interrupted export can be resumed. A task with
 * any resume state is resumable; {@code shardNo} is {@code 0} for single-cursor tasks.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ResumeState {

    private Integer shardNo;

    private String kind;

    private String cursorJson;

    private Long rowsDone;

    private Long bytesDone;

    private Date updatedAt;
}

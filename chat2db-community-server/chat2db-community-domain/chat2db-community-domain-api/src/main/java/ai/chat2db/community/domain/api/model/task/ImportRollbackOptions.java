package ai.chat2db.community.domain.api.model.task;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Optional rollback and rehearsal requests. A rehearsal must finish by rolling back its writes. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ImportRollbackOptions {

    private Boolean fullRollback;

    private Boolean rehearsal;
}

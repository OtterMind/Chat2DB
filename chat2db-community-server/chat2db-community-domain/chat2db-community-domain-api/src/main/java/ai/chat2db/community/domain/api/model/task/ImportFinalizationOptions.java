package ai.chat2db.community.domain.api.model.task;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Optional post-import database maintenance requests. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ImportFinalizationOptions {

    private Boolean resetSequences;

    private Boolean rebuildIndexes;

    private Boolean refreshStatistics;
}

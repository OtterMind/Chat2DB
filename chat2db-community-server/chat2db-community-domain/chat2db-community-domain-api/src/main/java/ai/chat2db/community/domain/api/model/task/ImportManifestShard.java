package ai.chat2db.community.domain.api.model.task;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** One resumable unit in an import manifest. Bounds are inclusive lower and exclusive upper. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ImportManifestShard {

    private String shardId;

    private String databaseName;

    private String schemaName;

    private String tableName;

    /** Canonical qualified node matching an entry in the dependency plan. */
    private String tableKey;

    private int layer;

    private String shardKey;

    private String lowerBound;

    private String upperBound;

    private String sourcePath;

    private long estimatedRows;

    private String expectedChecksum;

    private List<String> dependencyShardIds;
}

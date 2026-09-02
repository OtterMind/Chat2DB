package ai.chat2db.community.domain.api.model.task;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExportTaskSpec implements TaskSpec {

    private String taskType;

    private String taskName;

    private TaskTargetSnapshot target;

    private List<String> tableNames;

    private String sql;

    private String originalSql;

    private Integer resultSetId;

    private String exportSize;

    private String format;

    /**
     * Optional post-format compression, see {@link TaskCompression}; null or blank means none.
     */
    private String compression;

    private String scope;

    private Boolean containData;

    private Boolean containsHeader;

    private String exportPath;

    private String suggestedFileName;

    /**
     * Rows per resume checkpoint; {@code null} or non-positive disables checkpointing and keeps the
     * single-statement streaming path.
     */
    private Integer checkpointRows;
}

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

    private String scope;

    private Boolean containData;

    private Boolean containsHeader;

    /** CSV character set; absent keeps the {@code UTF-8} default of {@link ExportCsvOptions}. */
    private String encoding;

    /** CSV field delimiter; absent keeps the {@code ,} default of {@link ExportCsvOptions}. */
    private String delimiter;

    private String exportPath;

    private String suggestedFileName;
}

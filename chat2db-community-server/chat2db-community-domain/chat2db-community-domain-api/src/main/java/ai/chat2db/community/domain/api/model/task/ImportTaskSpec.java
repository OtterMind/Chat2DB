package ai.chat2db.community.domain.api.model.task;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ImportTaskSpec implements TaskSpec {

    private String taskType;

    private String taskName;

    private TaskTargetSnapshot target;

    private String sourceFile;

    /** Opaque ID for a server-staged source file owned by the import task. */
    private String importFileId;

    private String displayFileName;

    private String format;

    private String dataTimeFormat;

    /** Explicit source-to-target mapping from the preview workflow. */
    private List<Map<String, String>> columnMappings;

    private String unmappedTarget;

    /** Parser options shared with preview, including sheet/header/empty-cell behavior. */
    private Map<String, Object> importOptions;
}

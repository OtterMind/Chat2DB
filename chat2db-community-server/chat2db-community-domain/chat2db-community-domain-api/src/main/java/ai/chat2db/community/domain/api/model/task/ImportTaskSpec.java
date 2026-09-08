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
public class ImportTaskSpec implements TaskSpec {

    private String taskType;

    private String taskName;

    private TaskTargetSnapshot target;

    private String sourceFile;

    /** Opaque ID of a server-staged source file, when the import originated from preview. */
    private String importFileId;

    private String displayFileName;

    private String format;

    private String dataTimeFormat;

    /** Optional mapping supplied by the import-preview workflow. */
    private List<ImportColumnMapping> columnMappings;

    private UnmappedTargetStrategy unmappedTarget;
    /**
     * Optional behaviour overrides (encoding, delimiters, column mapping, error tolerance).
     */
    private ImportOptions options;

    /**
     * Execution mode; {@code null} resolves to {@code STANDARD}.
     */
    private String mode;
}

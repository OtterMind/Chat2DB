package ai.chat2db.community.domain.api.model.task;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** One source file and its target table in a schema- or database-scoped import. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ImportTableSource {

    private String databaseName;

    private String schemaName;

    private String tableName;

    private String sourceFile;

    /** Opaque ID of a server-staged source file. */
    private String importFileId;

    private String displayFileName;

    private String format;

    private String dataTimeFormat;

    private List<ImportColumnMapping> columnMappings;

    private UnmappedTargetStrategy unmappedTarget;

    private ImportOptions options;
}

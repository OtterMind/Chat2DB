package ai.chat2db.community.web.api.model.request.task;

import ai.chat2db.community.domain.api.model.task.ImportColumnMapping;
import ai.chat2db.community.domain.api.model.task.ImportOptions;
import ai.chat2db.community.domain.api.model.task.UnmappedTargetStrategy;
import lombok.Data;

import java.util.List;

/** One file-to-table mapping in a schema/database import request. */
@Data
public class TaskImportTableSourceRequest {

    private String databaseName;

    private String schemaName;

    private String tableName;

    private String sourceFile;

    private String fileId;

    private String displayFileName;

    private String format;

    private String dataTimeFormat;

    private List<ImportColumnMapping> columnMappings;

    private UnmappedTargetStrategy unmappedTarget;

    private ImportOptions options;
}

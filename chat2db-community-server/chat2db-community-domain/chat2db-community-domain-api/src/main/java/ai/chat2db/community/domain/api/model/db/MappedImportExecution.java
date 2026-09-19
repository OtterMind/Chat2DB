package ai.chat2db.community.domain.api.model.db;

import ai.chat2db.community.domain.api.model.task.ExcelOptions;
import ai.chat2db.community.domain.api.model.task.JsonOptions;
import ai.chat2db.community.domain.api.model.task.ImportColumnMapping;
import ai.chat2db.community.domain.api.model.task.CsvOptions;
import ai.chat2db.community.domain.api.model.task.UnmappedTargetStrategy;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MappedImportExecution {

    private Long dataSourceId;

    private String databaseName;

    private String schemaName;

    private String tableName;

    private String fileId;

    private CsvOptions csvOptions;

    private ExcelOptions excelOptions;

    private JsonOptions jsonOptions;

    private List<ImportColumnMapping> mappings;

    private UnmappedTargetStrategy unmappedTarget;

    private String mode;

}

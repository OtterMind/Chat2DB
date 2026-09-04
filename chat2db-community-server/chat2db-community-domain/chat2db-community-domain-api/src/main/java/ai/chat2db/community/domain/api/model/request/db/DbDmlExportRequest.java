package ai.chat2db.community.domain.api.model.request.db;

import ai.chat2db.community.domain.api.model.task.CsvOptions;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class DbDmlExportRequest {

    @NotBlank
    private String sql;

    @NotBlank
    private String exportType;

    private String databaseName;

    private String schemaName;

    private Integer resultSetId;

    private String exportSize;

    private String originalSql;

    private CsvOptions csvOptions;
}

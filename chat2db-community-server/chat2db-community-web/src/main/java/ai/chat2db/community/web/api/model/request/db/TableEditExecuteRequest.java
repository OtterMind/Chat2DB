package ai.chat2db.community.web.api.model.request.db;

import ai.chat2db.community.web.api.model.request.data.source.IDataSourceSchemaRequestInfo;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class TableEditExecuteRequest implements IDataSourceSchemaRequestInfo {

    @NotNull
    private Long dataSourceId;

    private String databaseName;

    private String schemaName;

    @NotBlank
    private String sql;
}

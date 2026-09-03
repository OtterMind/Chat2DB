package ai.chat2db.community.web.api.model.request.db;

import ai.chat2db.community.web.api.model.request.data.source.IDataSourceSchemaRequestInfo;
import ai.chat2db.community.web.api.model.request.data.source.IDataSourceConsoleRequestInfo;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class DdlExecuteRequest implements IDataSourceSchemaRequestInfo, IDataSourceConsoleRequestInfo {

    @NotNull
    private Long dataSourceId;

    private String databaseName;

    private String schemaName;

    private Long consoleId;

    @NotBlank
    private String sql;

    private String tableName;
}

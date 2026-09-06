package ai.chat2db.community.web.api.model.request.sql;

import ai.chat2db.community.web.api.model.request.data.source.IDataSourceConsoleRequestInfo;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class SqlExplainRequest implements IDataSourceConsoleRequestInfo {

    @NotNull
    private Long dataSourceId;

    private String databaseName;

    private String schemaName;

    private Long consoleId;

    @NotBlank
    @Size(max = 50000)
    private String sql;

    @NotBlank
    @Size(max = 80)
    private String requestId;
}

package ai.chat2db.community.web.api.model.request.db;

import ai.chat2db.community.web.api.model.request.data.source.IDataSourceConsoleRequestInfo;
import ai.chat2db.community.web.api.model.request.data.source.IDataSourceSchemaRequestInfo;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class SqlEditorExecuteRequest implements IDataSourceConsoleRequestInfo, IDataSourceSchemaRequestInfo {

    @NotNull
    private Long dataSourceId;

    private String databaseName;

    private String schemaName;

    @NotBlank
    private String sql;

    private Long consoleId;

    private Long applyId;

    @Min(1)
    private Integer pageNo;

    @Min(1)
    private Integer pageSize;

    private boolean single;

    private Integer resultSetId;

    private Boolean errorContinue;

    private boolean explain;
}

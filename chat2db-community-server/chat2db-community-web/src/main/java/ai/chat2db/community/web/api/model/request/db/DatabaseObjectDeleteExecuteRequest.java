package ai.chat2db.community.web.api.model.request.db;

import ai.chat2db.community.web.api.model.request.data.source.IDataSourceBaseRequestInfo;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class DatabaseObjectDeleteExecuteRequest implements IDataSourceBaseRequestInfo {

    @NotNull
    private Long dataSourceId;

    private String databaseName;

    private String schemaName;

    private String tablespaceName;

    @NotBlank
    private String confirmName;
}

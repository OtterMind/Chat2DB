package ai.chat2db.server.web.api.controller.rdb.request;

import ai.chat2db.server.web.api.controller.data.source.request.DataSourceBaseRequestInfo;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class UpdateVirtualFKRequest implements DataSourceBaseRequestInfo {

    @NotNull
    private Long id;

    @NotNull
    private Long dataSourceId;

    private String databaseName;

    private String schemaName;

    private String comment;

    private String tableName;

    private String columnName;

    private String referencedTable;

    private String referencedColumnName;

    private String vkName;
}

package ai.chat2db.community.web.api.model.request.db;

import jakarta.validation.constraints.NotNull;

import ai.chat2db.community.web.api.model.request.data.source.DataSourceBaseRequest;
import ai.chat2db.community.web.api.model.request.data.source.IDataSourceConsoleRequestInfo;

import lombok.Data;


@Data
public class DdlCountRequest extends DataSourceBaseRequest implements IDataSourceConsoleRequestInfo {


    @NotNull
    private String sql;


    /**
     * Optional: table-data pages opened from the tree have no SQL console.
     * The count implementation resolves its execution context from
     * {@code dataSourceId}/{@code databaseName} and does not read this field.
     */
    private Long consoleId;


    /**
     * Optional: console results carry only {@code sql}. Only the MongoDB
     * branch of the count implementation needs the collection name.
     */
    private String tableName;
}

package ai.chat2db.community.web.api.model.request.db;

import ai.chat2db.community.web.api.model.request.data.source.IDataSourceBaseRequestInfo;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class TablespaceDeletePrepareRequest implements IDataSourceBaseRequestInfo {

    @NotNull
    private Long dataSourceId;

    /**
     * Unused for tablespaces (instance-level), but required by {@link IDataSourceBaseRequestInfo}.
     */
    private String databaseName;

    private String tablespaceName;
}

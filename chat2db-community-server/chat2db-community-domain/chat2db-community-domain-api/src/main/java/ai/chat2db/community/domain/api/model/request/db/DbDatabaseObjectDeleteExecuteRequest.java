package ai.chat2db.community.domain.api.model.request.db;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class DbDatabaseObjectDeleteExecuteRequest {

    @NotNull
    private Long dataSourceId;

    private String databaseName;

    private String schemaName;

    private String tablespaceName;

    private String confirmName;
}

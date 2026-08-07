package ai.chat2db.community.domain.api.model.request.db;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class DbTablespaceDeletePrepareRequest {

    @NotNull
    private Long dataSourceId;

    private String tablespaceName;
}

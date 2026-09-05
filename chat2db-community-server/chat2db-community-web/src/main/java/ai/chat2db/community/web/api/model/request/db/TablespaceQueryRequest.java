package ai.chat2db.community.web.api.model.request.db;

import ai.chat2db.community.web.api.model.request.data.source.DataSourceBaseRequest;
import lombok.Data;

@Data
public class TablespaceQueryRequest extends DataSourceBaseRequest {

    private String tablespaceName;
}

package ai.chat2db.community.web.api.model.request.db;

import ai.chat2db.community.web.api.model.request.data.source.DataSourceBaseRequest;
import jakarta.validation.constraints.NotNull;
import lombok.Data;


@Data
public class TableDetailQueryRequest extends DataSourceBaseRequest {


    @NotNull
    private String tableName;

    private String operationType;

}

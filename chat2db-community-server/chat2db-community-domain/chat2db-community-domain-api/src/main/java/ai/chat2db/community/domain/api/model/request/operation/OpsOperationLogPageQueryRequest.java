package ai.chat2db.community.domain.api.model.request.operation;

import ai.chat2db.community.tools.wrapper.param.PageQueryParam;
import jakarta.validation.constraints.Size;

import lombok.Data;


@Data
public class OpsOperationLogPageQueryRequest extends PageQueryParam {


    private Long userId;


    @Size(max = 256)
    private String searchKey;


    private Long dataSourceId;


    private String databaseName;


    private String schemaName;


    private String operationType;
}

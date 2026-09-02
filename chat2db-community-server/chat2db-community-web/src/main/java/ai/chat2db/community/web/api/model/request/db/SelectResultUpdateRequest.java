package ai.chat2db.community.web.api.model.request.db;

import ai.chat2db.community.domain.api.model.request.db.SelectResultOperation;
import ai.chat2db.community.web.api.model.request.data.source.DataSourceBaseRequest;
import ai.chat2db.community.web.api.model.request.data.source.IDataSourceConsoleRequestInfo;
import ai.chat2db.community.domain.api.model.result.Header;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class SelectResultUpdateRequest extends DataSourceBaseRequest implements IDataSourceConsoleRequestInfo {


    private List<Header> headerList;


    @NotEmpty
    private List<SelectResultOperation> operations;


    private String tableName;


    private Long consoleId;


    private Map<String,Object> extra;
    @Override
    public Long getConsoleId() {
        return consoleId;
    }

}

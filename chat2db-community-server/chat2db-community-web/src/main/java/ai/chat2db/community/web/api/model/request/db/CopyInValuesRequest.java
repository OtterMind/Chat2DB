package ai.chat2db.community.web.api.model.request.db;

import ai.chat2db.community.domain.api.model.request.db.SelectResultOperation;
import ai.chat2db.community.web.api.model.request.data.source.DataSourceBaseRequest;
import ai.chat2db.community.web.api.model.request.data.source.IDataSourceConsoleRequestInfo;
import ai.chat2db.community.domain.api.model.result.Header;
import lombok.Data;

import java.util.List;

@Data
public class CopyInValuesRequest extends DataSourceBaseRequest implements IDataSourceConsoleRequestInfo {

    private List<Header> headerList;

    private List<SelectResultOperation> operations;

    private List<String> externalValues;

    private String sourceType;

    private Long consoleId;

    @Override
    public Long getConsoleId() {
        return consoleId;
    }
}

package ai.chat2db.community.web.api.model.request.data.source;

import jakarta.validation.constraints.NotNull;
import lombok.Data;


@Data
public class ConsoleCloseRequest extends DataSourceBaseRequest implements IDataSourceConsoleRequestInfo{


    @NotNull
    private Long consoleId;
}

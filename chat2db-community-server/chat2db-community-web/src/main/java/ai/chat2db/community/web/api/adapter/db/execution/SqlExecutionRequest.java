package ai.chat2db.community.web.api.adapter.db.execution;

import ai.chat2db.community.tools.console.ConsoleMessage;
import ai.chat2db.community.domain.api.model.runtime.ConnectionProfile;
import ai.chat2db.community.domain.api.model.request.runtime.DbConnectionContextRequest;
import ai.chat2db.community.web.api.model.request.db.SqlEditorExecuteRequest;
import ai.chat2db.community.tools.model.Context;
import lombok.Builder;
import lombok.Data;

import java.util.Map;

@Data
@Builder
public class SqlExecutionRequest {

    private String requestUuid;

    private String executionId;

    private String laneId;

    private SqlEditorExecuteRequest sqlEditorRequest;

    private ConsoleMessage consoleMessage;

    private Context context;

    private DbConnectionContextRequest connectionContext;

    private ConnectionProfile connectionProfile;

    private Map<String, Object> headers;
}

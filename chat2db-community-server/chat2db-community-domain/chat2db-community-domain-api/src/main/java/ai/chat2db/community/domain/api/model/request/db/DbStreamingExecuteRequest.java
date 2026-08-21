package ai.chat2db.community.domain.api.model.request.db;

import ai.chat2db.community.domain.api.service.db.ISqlExecutionCancellation;
import ai.chat2db.community.domain.api.service.db.ISqlExecutionResultConsumer;
import ai.chat2db.community.domain.api.service.db.ISqlExecutionStatementListener;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class DbStreamingExecuteRequest {

    @NotNull
    private String executionId;

    @NotNull
    private DbDlExecuteRequest dlExecuteRequest;

    @NotNull
    private ISqlExecutionResultConsumer consumer;

    @NotNull
    private ISqlExecutionStatementListener statementListener;

    @NotNull
    private ISqlExecutionCancellation cancellation;
}

package ai.chat2db.spi.model.request;

import ai.chat2db.community.domain.api.service.db.ISqlExecutionStatementListener;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.sql.Connection;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SqlStatementExecuteRequest {

    @NotBlank
    private String sql;

    @NotNull
    private Connection connection;

    private boolean limitRowSize;

    private Integer offset;

    private Integer count;

    private ISqlExecutionStatementListener statementListener;

    private Runnable cancellationChecker;
}

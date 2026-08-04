package ai.chat2db.spi.model.request;

import ai.chat2db.community.domain.api.service.db.ISqlExecutionStatementListener;
import ai.chat2db.spi.IResultSetConsumer;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.sql.Connection;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FetchAllTableRecordsRequest {

    @NotNull
    private Connection connection;

    @NotBlank
    private String sql;

    @Positive
    private int batchSize;

    @NotNull
    private IResultSetConsumer consumer;

    private ISqlExecutionStatementListener statementListener;

    private Runnable cancellationChecker;
}

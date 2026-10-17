package ai.chat2db.plugin.informix;

import ai.chat2db.community.domain.api.enums.plugin.SqlTypeEnum;
import ai.chat2db.community.domain.api.model.result.ExecuteResponse;
import ai.chat2db.community.domain.api.model.result.ExecutionContext;
import ai.chat2db.community.domain.api.model.result.Header;
import ai.chat2db.community.domain.api.model.result.ResultCell;
import ai.chat2db.community.domain.api.model.sql.SimpleSqlStatement;
import ai.chat2db.community.domain.api.service.db.ISqlExecutionCancellation;
import ai.chat2db.community.domain.api.service.db.ISqlExecutionResultConsumer;
import ai.chat2db.community.domain.api.service.db.ISqlExecutionStatementListener;
import ai.chat2db.spi.DefaultSQLExecutor;
import ai.chat2db.plugin.informix.parser.InformixSqlParser;
import ai.chat2db.spi.model.ExecutionTiming;
import ai.chat2db.spi.sql.Chat2DBContext;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public final class InformixCommandExecutor extends DefaultSQLExecutor {

    public static final InformixCommandExecutor INSTANCE = new InformixCommandExecutor();

    private final InformixExplainClient explainClient = new InformixExplainClient();

    private InformixCommandExecutor() {
    }

    @Override
    protected List<ExecuteResponse> executeMulti(SimpleSqlStatement statement, Connection connection,
                                                 boolean limitRowSize, Integer offset, Integer count,
                                                 Integer resultSetId, ExecutionContext executionContext)
            throws SQLException {
        String sql = InformixSqlParser.extractExplainSql(statement.getSql());
        if (sql == null) {
            return super.executeMulti(statement, connection, limitRowSize, offset, count, resultSetId, executionContext);
        }

        markStatementAsExplain(statement);
        Chat2DBContext.guardStatement(statement.getSql());
        long startedAtEpochMs = System.currentTimeMillis();
        long executeStartedNanos = System.nanoTime();
        String plan = explainClient.getExplainInfo(connection, sql);
        long executeDurationNanos = ExecutionTiming.elapsedNanos(executeStartedNanos);
        return List.of(buildExplainResponse(statement.getSql(), plan, executionContext, startedAtEpochMs, executeDurationNanos));
    }

    @Override
    protected List<ExecuteResponse> executeMultiStreaming(SimpleSqlStatement statement, Connection connection,
                                                          boolean limitRowSize, Integer offset, Integer count,
                                                          Integer resultSetId, ISqlExecutionResultConsumer consumer,
                                                          ISqlExecutionStatementListener statementListener,
                                                          ISqlExecutionCancellation cancellation, SqlTypeEnum sqlType,
                                                          String originalSql, int pageNo, int pageSize,
                                                          AtomicInteger streamResultSequence, int statementSequence,
                                                          ExecutionContext executionContext)
            throws SQLException {
        String sql = InformixSqlParser.extractExplainSql(statement.getSql());
        if (sql == null) {
            return super.executeMultiStreaming(statement, connection, limitRowSize, offset, count, resultSetId,
                    consumer, statementListener, cancellation, sqlType, originalSql, pageNo, pageSize,
                    streamResultSequence, statementSequence, executionContext);
        }

        markStatementAsExplain(statement);
        checkCanceled(cancellation);
        Chat2DBContext.guardStatement(statement.getSql());
        long startedAtEpochMs = System.currentTimeMillis();
        long executeStartedNanos = System.nanoTime();
        String plan = explainClient.getExplainInfo(connection, sql, statementListener, cancellation);
        long executeDurationNanos = ExecutionTiming.elapsedNanos(executeStartedNanos);
        checkCanceled(cancellation);

        ExecuteResponse response = buildExplainResponse(
                statement.getSql(), plan, executionContext, startedAtEpochMs, executeDurationNanos);
        response.setPageNo(pageNo);
        response.setPageSize(response.getDataList().size());
        response.setHasNextPage(Boolean.FALSE);
        response.setFuzzyTotal(Integer.toString(response.getDataList().size()));
        publishMaterializedQueryResult(response, consumer, streamResultSequence, statementSequence, pageNo, pageSize);
        return List.of(response);
    }

    private ExecuteResponse buildExplainResponse(String sql, String plan,
                                                 ExecutionContext executionContext,
                                                 long startedAtEpochMs, long executeDurationNanos) {
        List<Header> headerList = new ArrayList<>();
        headerList.add(Header.builder()
                .name("Execution Plan")
                .dataType("VARCHAR")
                .build());

        List<ResultCell> row = new ArrayList<>();
        row.add(ResultCell.of(plan));

        List<List<ResultCell>> dataList = new ArrayList<>();
        dataList.add(row);

        return ExecuteResponse.builder()
                .success(Boolean.TRUE)
                .sql(sql)
                .originalSql(sql)
                .sqlType(ai.chat2db.community.domain.api.enums.parser.SqlTypeEnum.EXPLAIN.name())
                .headerList(headerList)
                .dataList(dataList)
                .hasNextPage(Boolean.FALSE)
                .executionContext(executionContext)
                .executionMetrics(ExecutionTiming.complete(
                        ExecutionTiming.started(startedAtEpochMs), executeDurationNanos, 0L, 1))
                .build();
    }

    private void checkCanceled(ISqlExecutionCancellation cancellation) throws SQLException {
        if (cancellation != null && cancellation.isCanceled()) {
            throw new SQLException("SQL execution canceled");
        }
    }
}

package ai.chat2db.plugin.oracle;

import ai.chat2db.community.domain.api.enums.plugin.SqlTypeEnum;
import ai.chat2db.community.domain.api.model.result.ExecuteResponse;
import ai.chat2db.community.domain.api.model.result.ExecutionContext;
import ai.chat2db.community.domain.api.model.sql.SimpleSqlStatement;
import ai.chat2db.community.domain.api.service.db.ISqlExecutionCancellation;
import ai.chat2db.community.domain.api.service.db.ISqlExecutionResultConsumer;
import ai.chat2db.community.domain.api.service.db.ISqlExecutionStatementListener;
import ai.chat2db.plugin.oracle.parser.OracleExplainParser;
import ai.chat2db.spi.DefaultSQLExecutor;
import ai.chat2db.spi.sql.Chat2DBContext;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Executes Oracle commands and turns {@code EXPLAIN PLAN} into a readable plan.
 *
 * <p>Oracle writes an explained plan into the plan table instead of returning
 * it, so the default JDBC path reports the command as executed and shows no
 * rows at all.
 */
public final class OracleCommandExecutor extends DefaultSQLExecutor {

    public static final OracleCommandExecutor INSTANCE = new OracleCommandExecutor();

    private final OracleExplainClient explainClient = new OracleExplainClient();

    private OracleCommandExecutor() {
    }

    @Override
    protected List<ExecuteResponse> executeMulti(SimpleSqlStatement statement, Connection connection,
                                                 boolean limitRowSize, Integer offset, Integer count,
                                                 Integer resultSetId, ExecutionContext executionContext)
            throws SQLException {
        String explainedSql = OracleExplainParser.extractExplainedSql(statement.getSql());
        if (explainedSql == null) {
            return super.executeMulti(statement, connection, limitRowSize, offset, count, resultSetId,
                    executionContext);
        }

        markStatementAsExplain(statement);
        Chat2DBContext.guardStatement(statement.getSql());
        return List.of(explainClient.explain(connection, explainedSql, executionContext));
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
        String explainedSql = OracleExplainParser.extractExplainedSql(statement.getSql());
        if (explainedSql == null) {
            return super.executeMultiStreaming(statement, connection, limitRowSize, offset, count, resultSetId,
                    consumer, statementListener, cancellation, sqlType, originalSql, pageNo, pageSize,
                    streamResultSequence, statementSequence, executionContext);
        }

        markStatementAsExplain(statement);
        checkCanceled(cancellation);
        Chat2DBContext.guardStatement(statement.getSql());
        ExecuteResponse response = explainClient.explain(connection, explainedSql, executionContext);
        checkCanceled(cancellation);

        response.setPageNo(pageNo);
        response.setPageSize(response.getDataList().size());
        response.setHasNextPage(Boolean.FALSE);
        response.setFuzzyTotal(Integer.toString(response.getDataList().size()));
        publishMaterializedQueryResult(response, consumer, streamResultSequence, statementSequence, pageNo,
                pageSize);
        return List.of(response);
    }

    private void markStatementAsExplain(SimpleSqlStatement statement) {
        // Outer execute/executeStreaming overwrite response.sqlType from statement.sqlType
        // after setExplain() rewrote the SQL text without updating the type.
        statement.setSqlType(ai.chat2db.community.domain.api.enums.parser.SqlTypeEnum.EXPLAIN.name());
    }

    private void checkCanceled(ISqlExecutionCancellation cancellation) throws SQLException {
        if (cancellation != null && cancellation.isCanceled()) {
            throw new SQLException("SQL execution canceled");
        }
    }
}

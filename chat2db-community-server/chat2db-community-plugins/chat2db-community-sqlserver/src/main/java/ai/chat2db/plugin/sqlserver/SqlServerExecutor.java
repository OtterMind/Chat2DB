package ai.chat2db.plugin.sqlserver;

import ai.chat2db.community.domain.api.model.sql.SqlExecuteRequest;
import ai.chat2db.community.domain.api.config.DBConfig;
import ai.chat2db.community.domain.api.model.result.ExecutionContext;
import ai.chat2db.community.domain.api.model.result.ExecuteResponse;
import ai.chat2db.community.domain.api.model.sql.SimpleSqlStatement;
import ai.chat2db.community.domain.api.service.db.ISqlExecutionCancellation;
import ai.chat2db.community.domain.api.service.db.ISqlExecutionResultConsumer;
import ai.chat2db.community.domain.api.service.db.ISqlExecutionStatementListener;
import ai.chat2db.spi.model.ExecutionTiming;
import ai.chat2db.spi.model.JdbcExecutionContext;
import ai.chat2db.spi.model.request.SqlStatementExecuteRequest;
import ai.chat2db.spi.DefaultSQLExecutor;
import ai.chat2db.spi.util.SqlUtils;
import com.alibaba.druid.DbType;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

public class SqlServerExecutor extends DefaultSQLExecutor {

    private record Range(int start, int end) {
    }



    @Override
    public List<ExecuteResponse> execute(SqlExecuteRequest command) {
        prepareCommandScript(command);
        return super.execute(command);
    }

    @Override
    public void executeStreaming(SqlExecuteRequest command, ISqlExecutionResultConsumer consumer,
                                 ISqlExecutionStatementListener statementListener,
                                 ISqlExecutionCancellation cancellation) throws SQLException {
        prepareCommandScript(command);
        super.executeStreaming(command, consumer, statementListener, cancellation);
    }

    @Override
    protected List<SimpleSqlStatement> buildSimpleSqlStatements(SqlExecuteRequest command, DbType dbType,
                                                                 String type, DBConfig dbConfig) {
        List<String> sqlList = splitByGO(command.getScript());
        if (hasGoDelimiter(command.getScript())) {
            List<SimpleSqlStatement> statements = new ArrayList<>(sqlList.size());
            for (String sql : sqlList) {
                List<SimpleSqlStatement> parsedStatements = SqlUtils.parseStatements(sql, dbType, type);
                if (shouldUsePreservedCompositeStatement(command, dbConfig, parsedStatements)) {
                    statements.add(preservedCompositeStatement(sql));
                } else if (parsedStatements.size() == 1) {
                    SimpleSqlStatement statement = parsedStatements.get(0);
                    statement.setSql(sql);
                    statements.add(statement);
                } else {
                    statements.add(new SimpleSqlStatement(sql));
                }
            }
            return statements;
        }
        return super.buildSimpleSqlStatements(command, dbType, type, dbConfig);
    }

    @Override
    protected ExecutionContext executionContextAfterExecute(SimpleSqlStatement statement, Connection connection,
                                                             ExecutionContext statementStartContext) {
        return isPreservedCompositeStatement(statement)
                ? JdbcExecutionContext.capture(connection)
                : statementStartContext;
    }

    void prepareCommandScript(SqlExecuteRequest command) {
        String sql = command.getScript();
        if (!command.isExplain() && splitByGO(sql).size() <= 1) {
            command.setScript(removeSpecialGO(sql));
        }
    }


    private String removeSpecialGO(String sql) {
        if (StringUtils.isBlank(sql)) {
            return null;
        }
        StringBuilder cleaned = new StringBuilder(sql.length());
        int start = 0;
        for (Range range : findGoDelimiterRanges(sql)) {
            cleaned.append(sql, start, range.start());
            start = range.end();
        }
        cleaned.append(sql, start, sql.length());
        return cleaned.toString();
    }

    List<String> splitByGO(String sql) {
        List<String> sqlList = new ArrayList<>();
        if (StringUtils.isBlank(sql)) {
            return sqlList;
        }
        int start = 0;
        for (Range range : findGoDelimiterRanges(sql)) {
            String item = sql.substring(start, range.start()).trim();
            if (StringUtils.isNotBlank(item)) {
                sqlList.add(item);
            }
            start = range.end();
        }
        String item = sql.substring(start).trim();
        if (StringUtils.isNotBlank(item)) {
            sqlList.add(item);
        }
        return sqlList;
    }

    private boolean hasGoDelimiter(String sql) {
        return StringUtils.isNotBlank(sql) && !findGoDelimiterRanges(sql).isEmpty();
    }

    private List<Range> findGoDelimiterRanges(String sql) {
        List<Range> ranges = new ArrayList<>();
        int lineStart = 0;
        while (lineStart < sql.length()) {
            int lineEnd = lineStart;
            while (lineEnd < sql.length() && sql.charAt(lineEnd) != '\n' && sql.charAt(lineEnd) != '\r') {
                lineEnd++;
            }
            findGoDelimiterRange(sql, lineStart, lineEnd, ranges);
            if (lineEnd < sql.length() && sql.charAt(lineEnd) == '\r' && lineEnd + 1 < sql.length()
                    && sql.charAt(lineEnd + 1) == '\n') {
                lineStart = lineEnd + 2;
            } else {
                lineStart = lineEnd + 1;
            }
        }
        return ranges;
    }

    private void findGoDelimiterRange(String sql, int lineStart, int lineEnd, List<Range> ranges) {
        int candidate = skipHorizontalWhitespace(sql, lineStart, lineEnd);
        if (isGoDelimiter(sql, candidate, lineEnd)) {
            ranges.add(new Range(candidate, lineEnd));
            return;
        }
        for (int i = lineStart; i < lineEnd; i++) {
            if (sql.charAt(i) == ';') {
                candidate = skipHorizontalWhitespace(sql, i + 1, lineEnd);
                if (isGoDelimiter(sql, candidate, lineEnd)) {
                    ranges.add(new Range(candidate, lineEnd));
                    return;
                }
            }
        }
    }

    private int skipHorizontalWhitespace(String sql, int index, int end) {
        while (index < end && (sql.charAt(index) == ' ' || sql.charAt(index) == '\t')) {
            index++;
        }
        return index;
    }

    private boolean isGoDelimiter(String sql, int index, int lineEnd) {
        if (index + 2 > lineEnd || Character.toLowerCase(sql.charAt(index)) != 'g'
                || Character.toLowerCase(sql.charAt(index + 1)) != 'o') {
            return false;
        }
        int cursor = index + 2;
        if (cursor < lineEnd && isIdentifierPart(sql.charAt(cursor))) {
            return false;
        }
        cursor = skipHorizontalWhitespace(sql, cursor, lineEnd);
        if (cursor < lineEnd && sql.charAt(cursor) == ';') {
            cursor = skipHorizontalWhitespace(sql, cursor + 1, lineEnd);
        }
        if (cursor + 1 < lineEnd && sql.charAt(cursor) == '-' && sql.charAt(cursor + 1) == '-') {
            return true;
        }
        return skipHorizontalWhitespace(sql, cursor, lineEnd) == lineEnd;
    }

    private boolean isIdentifierPart(char value) {
        return Character.isLetterOrDigit(value) || value == '_';
    }


    @Override
    public ExecuteResponse executeUpdate(String sql, Connection connection, int n) throws SQLException {
        sql = removeSpecialGO(sql);
        return super.executeUpdate(sql, connection, n);
    }


    public ExecuteResponse execute(SqlStatementExecuteRequest request)
            throws SQLException {
        String sql = request.getSql();
        List<String> sqlList = splitByGO(sql);
        if (sqlList.size() > 1) {
            return executeSqlServerBatch(sql, sqlList, request.getConnection(), request.isLimitRowSize(),
                    request.getOffset(), request.getCount());
        }
        return super.execute(SqlStatementExecuteRequest.builder()
                .sql(removeSpecialGO(sql))
                .connection(request.getConnection())
                .limitRowSize(request.isLimitRowSize())
                .offset(request.getOffset())
                .count(request.getCount())
                .build());
    }

    @Override
    protected List<ExecuteResponse> executeMulti(SimpleSqlStatement simpleSqlStatement, Connection connection,
                                               boolean limitRowSize, Integer offset, Integer count, Integer resultSetId)
            throws SQLException {
        return executeMulti(simpleSqlStatement, connection, limitRowSize, offset, count, resultSetId,
                JdbcExecutionContext.capture(connection));
    }

    @Override
    protected List<ExecuteResponse> executeMulti(SimpleSqlStatement simpleSqlStatement, Connection connection,
                                               boolean limitRowSize, Integer offset, Integer count, Integer resultSetId,
                                               ExecutionContext executionContext) throws SQLException {
        List<String> sqlList = splitByGO(simpleSqlStatement.getSql());
        if (sqlList.size() <= 1) {
            simpleSqlStatement.setSql(removeSpecialGO(simpleSqlStatement.getSql()));
            return super.executeMulti(simpleSqlStatement, connection, limitRowSize, offset, count, resultSetId,
                    executionContext);
        }
        return executeSqlServerBatch(simpleSqlStatement.getSql(), sqlList, connection, limitRowSize, offset, count,
                resultSetId);
    }

    private ExecuteResponse executeSqlServerBatch(String originalSql, List<String> sqlList, Connection connection,
                                                boolean limitRowSize, Integer offset, Integer count)
            throws SQLException {
        List<ExecuteResponse> executeResults = executeSqlServerBatch(originalSql, sqlList, connection, limitRowSize,
                offset, count, null);
        if (executeResults.isEmpty()) {
            return ExecuteResponse.builder().sql(originalSql).success(Boolean.TRUE).build();
        }
        return executeResults.get(executeResults.size() - 1);
    }

    private List<ExecuteResponse> executeSqlServerBatch(String originalSql, List<String> sqlList, Connection connection,
                                                      boolean limitRowSize, Integer offset, Integer count,
                                                      Integer resultSetId)
            throws SQLException {
        List<ExecuteResponse> executeResults = new ArrayList<>();
        ExecuteResponse executeResult = ExecuteResponse.builder().sql(originalSql).success(Boolean.TRUE).build();
        int resultCount = 0;
        for (String sql : sqlList) {
            try (PreparedStatement stmt = prepareClientSql(connection, sql)) {
                ExecutionContext executionContext = JdbcExecutionContext.capture(connection);
                long startedAtEpochMs = System.currentTimeMillis();
                long executeStartedNanos = System.nanoTime();
                boolean query = stmt.execute();
                long executeDurationNanos = ExecutionTiming.elapsedNanos(executeStartedNanos);
                while (true) {
                    executeResult = ExecuteResponse.builder().sql(originalSql).success(Boolean.TRUE).build();
                    long fetchDurationNanos = 0L;
                    if (query) {
                        resultCount++;
                        if (resultSetId == null || resultCount == resultSetId) {
                            long fetchStartedNanos = System.nanoTime();
                            executeResult = generateQueryExecuteResponse(stmt, limitRowSize, offset, count);
                            fetchDurationNanos = ExecutionTiming.elapsedNanos(fetchStartedNanos);
                            executeResult.setResultSetId(resultCount);
                        }
                    } else {
                        int updateCount = stmt.getUpdateCount();
                        if (updateCount == -1) {
                            break;
                        }
                        executeResult.setUpdateCount(updateCount);
                    }
                    executeResult.setSql(originalSql);
                    executeResult.setStatementSequence(1);
                    executeResult.setExecutionContext(executionContext);
                    executeResult.setExecutionMetrics(ExecutionTiming.complete(
                            ExecutionTiming.started(startedAtEpochMs), executeDurationNanos, fetchDurationNanos,
                            CollectionUtils.size(executeResult.getDataList())));
                    executeResults.add(executeResult);
                    long nextStartedAtEpochMs = System.currentTimeMillis();
                    long nextExecuteStartedNanos = System.nanoTime();
                    query = stmt.getMoreResults();
                    long nextExecuteDurationNanos = ExecutionTiming.elapsedNanos(nextExecuteStartedNanos);
                    if (!query && stmt.getUpdateCount() == -1) {
                        break;
                    }
                    startedAtEpochMs = nextStartedAtEpochMs;
                    executeDurationNanos = nextExecuteDurationNanos;
                }
            }
        }
        return executeResults;
    }
}

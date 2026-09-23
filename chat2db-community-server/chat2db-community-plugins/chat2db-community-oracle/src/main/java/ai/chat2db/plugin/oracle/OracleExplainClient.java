package ai.chat2db.plugin.oracle;

import ai.chat2db.community.domain.api.enums.parser.SqlTypeEnum;
import ai.chat2db.community.domain.api.model.result.ExecuteResponse;
import ai.chat2db.community.domain.api.model.result.ExecutionContext;
import ai.chat2db.community.domain.api.model.result.Header;
import ai.chat2db.community.domain.api.model.result.ResultCell;
import ai.chat2db.spi.model.ExecutionTiming;
import org.apache.commons.lang3.StringUtils;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static ai.chat2db.plugin.oracle.constant.OracleExplainConstants.*;

/**
 * Runs Oracle {@code EXPLAIN PLAN} and reads the resulting plan.
 *
 * <p>{@code EXPLAIN PLAN ... FOR <statement>} writes plan rows into the plan
 * table and returns no result set at all, so the plan is read back with a
 * separate query that renders those rows through {@code DBMS_XPLAN}.
 */
final class OracleExplainClient {

    ExecuteResponse explain(Connection connection, String explainedSql, ExecutionContext executionContext)
            throws SQLException {
        requirePlanTable(connection);
        String statementId = newStatementId();
        // Plan rows only stay readable while the write is part of one transaction,
        // so auto commit connections are held inside a transaction until the plan
        // has been read. Caller managed transactions are left untouched.
        boolean transactionManaged = connection.getAutoCommit();
        if (transactionManaged) {
            connection.setAutoCommit(false);
        }
        try {
            long startedAtEpochMs = System.currentTimeMillis();
            long executeStartedNanos = System.nanoTime();
            runExplainPlan(connection, statementId, explainedSql);
            long executeDurationNanos = ExecutionTiming.elapsedNanos(executeStartedNanos);

            ResultTable plan = queryPlan(connection, statementId);
            if (plan.isUnavailable()) {
                throw new SQLException("Oracle EXPLAIN PLAN wrote no readable plan into " + PLAN_TABLE
                        + " for statement id " + statementId);
            }
            return buildResponse(plan, executionContext, startedAtEpochMs, executeDurationNanos);
        } finally {
            restoreTransaction(connection, transactionManaged);
        }
    }

    private static void requirePlanTable(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(PLAN_TABLE_PROBE_SQL)) {
            // The probe only proves that the plan table can be read and written.
        } catch (SQLException e) {
            throw new SQLException("Oracle plan table " + PLAN_TABLE + " is not available for the current user."
                    + " Create it with @?/rdbms/admin/utlxplan.sql and run EXPLAIN PLAN again.", e);
        }
    }

    private static void runExplainPlan(Connection connection, String statementId, String explainedSql)
            throws SQLException {
        String explainSql = String.format(EXPLAIN_PLAN_SQL, statementId) + explainedSql;
        try (Statement statement = connection.createStatement()) {
            statement.execute(explainSql);
        }
    }

    private static String newStatementId() {
        String uniqueId = UUID.randomUUID().toString().replace("-", "");
        return STATEMENT_ID_PREFIX + uniqueId.substring(0, STATEMENT_ID_MAX_LENGTH - STATEMENT_ID_PREFIX.length());
    }

    private static ResultTable queryPlan(Connection connection, String statementId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(DISPLAY_PLAN_SQL)) {
            statement.setString(1, statementId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return readTable(resultSet);
            }
        }
    }

    private static ResultTable readTable(ResultSet resultSet) throws SQLException {
        ResultSetMetaData metaData = resultSet.getMetaData();
        int columnCount = metaData.getColumnCount();
        List<Header> headerList = new ArrayList<>(columnCount);
        for (int i = 1; i <= columnCount; i++) {
            headerList.add(Header.builder()
                    .name(metaData.getColumnLabel(i))
                    .dataType(metaData.getColumnTypeName(i))
                    .build());
        }
        List<List<ResultCell>> dataList = new ArrayList<>();
        while (resultSet.next()) {
            List<ResultCell> row = new ArrayList<>(columnCount);
            for (int i = 1; i <= columnCount; i++) {
                row.add(ResultCell.of(resultSet.getString(i)));
            }
            dataList.add(row);
        }
        return new ResultTable(headerList, dataList);
    }

    private static ExecuteResponse buildResponse(ResultTable table, ExecutionContext executionContext,
                                                 long startedAtEpochMs, long executeDurationNanos) {
        return ExecuteResponse.builder()
                .success(Boolean.TRUE)
                .sqlType(SqlTypeEnum.EXPLAIN.name())
                .headerList(table.headerList())
                .dataList(table.dataList())
                .hasNextPage(Boolean.FALSE)
                .executionContext(executionContext)
                .executionMetrics(ExecutionTiming.complete(ExecutionTiming.started(startedAtEpochMs),
                        executeDurationNanos, 0L, table.dataList().size()))
                .build();
    }

    private static void restoreTransaction(Connection connection, boolean transactionManaged) {
        if (!transactionManaged) {
            return;
        }
        try {
            // The plan has been read already; rolling back only drops the plan rows
            // written by this call.
            connection.rollback();
        } catch (SQLException ignored) {
            // Restoring auto commit below is what keeps the connection reusable.
        }
        try {
            connection.setAutoCommit(true);
        } catch (SQLException ignored) {
            // The caller owns the connection and reports its own failures.
        }
    }

    private record ResultTable(List<Header> headerList, List<List<ResultCell>> dataList) {

        /**
         * {@code DBMS_XPLAN} answers an unknown statement id with a single
         * {@code Error:} line instead of an empty result.
         */
        boolean isUnavailable() {
            if (dataList.isEmpty() || dataList.get(0).isEmpty()) {
                return true;
            }
            return StringUtils.startsWith(dataList.get(0).get(0).getValue(), PLAN_ERROR_PREFIX);
        }
    }
}

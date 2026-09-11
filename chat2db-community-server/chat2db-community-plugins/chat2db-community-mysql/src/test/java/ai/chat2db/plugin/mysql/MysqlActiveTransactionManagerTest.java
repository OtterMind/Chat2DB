package ai.chat2db.plugin.mysql;

import ai.chat2db.community.domain.api.model.transaction.ActiveTransaction;
import ai.chat2db.community.domain.api.model.transaction.ActiveTransaction.LockMetadataSource;
import ai.chat2db.community.domain.api.model.transaction.ActiveTransaction.LockMetadataState;
import ai.chat2db.community.domain.api.model.transaction.ActiveTransaction.QueryState;
import ai.chat2db.community.tools.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static ai.chat2db.plugin.mysql.constant.MysqlActiveTransactionConstants.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MysqlActiveTransactionManagerTest {

    @Test
    void recognizesProcessPrivilegeFailuresThroughWrappedCauses() {
        SQLException denied = new SQLException(
                "Access denied; you need (at least one of) the PROCESS privilege",
                "42000",
                MYSQL_ERROR_SPECIFIC_ACCESS_DENIED);

        assertTrue(MysqlActiveTransactionManager.hasProcessPrivilegeError(
                new IllegalStateException("execution failed", denied)));
        assertFalse(MysqlActiveTransactionManager.hasProcessPrivilegeError(
                new SQLException("table not found", "42S02", MYSQL_ERROR_TABLE_NOT_FOUND)));
    }

    @Test
    void buildsLiveTransactionQueryWithMySql80LockWaitMetadata() {
        String sql = MysqlActiveTransactionManager.activeTransactionSql("w.requesting_lock_id", "JOIN wait_table w");

        assertTrue(sql.contains("FROM information_schema.innodb_trx t"));
        assertTrue(sql.contains("LEFT JOIN information_schema.processlist p ON t.trx_mysql_thread_id = p.ID"));
        assertTrue(sql.contains("w.requesting_lock_id"));
        assertTrue(sql.contains("JOIN wait_table w"));
        assertTrue(sql.contains("ORDER BY t.trx_started"));
    }

    @Test
    void unavailableLockMetadataQueryKeepsLiveTransactionSource() {
        MysqlActiveTransactionManager.LockMetadataQuery query =
                MysqlActiveTransactionManager.unavailableLockMetadataQuery();

        assertFalse(query.available());
        assertTrue(query.sql().contains("FROM information_schema.innodb_trx t"));
        assertTrue(query.sql().contains("NULL AS blocking_trx_id"));
        assertTrue(query.sql().contains("LEFT JOIN information_schema.processlist p"));
        assertTrue(query.sql().contains("ORDER BY t.trx_started"));
    }

    @Test
    void recognizesMissingOrDeniedLockMetadataFailures() {
        assertTrue(MysqlActiveTransactionManager.hasMissingOrDeniedMetadataError(
                new SQLException("Table 'performance_schema.data_lock_waits' doesn't exist",
                        "42S02", MYSQL_ERROR_TABLE_NOT_FOUND)));
        assertTrue(MysqlActiveTransactionManager.hasMissingOrDeniedMetadataError(
                new SQLException("SELECT command denied to user for table 'data_locks'",
                        "42000", MYSQL_ERROR_TABLE_ACCESS_DENIED)));
        assertFalse(MysqlActiveTransactionManager.hasMissingOrDeniedMetadataError(
                new SQLException("Syntax error", "42000", 1064)));
    }

    @Test
    void mapsResultSetToTypedTransactionResponse() throws SQLException {
        ResultSet resultSet = resultSet(Map.ofEntries(
                Map.entry(RESULT_TRX_ID, "421337"),
                Map.entry(RESULT_TRX_STATE, "LOCK WAIT"),
                Map.entry(RESULT_TRX_STARTED, Timestamp.valueOf("2026-08-31 12:00:00")),
                Map.entry(RESULT_TRX_AGE_SECONDS, 12L),
                Map.entry(RESULT_TRX_ISOLATION_LEVEL, "REPEATABLE READ"),
                Map.entry(RESULT_TRX_ROWS_LOCKED, 1L),
                Map.entry(RESULT_TRX_ROWS_MODIFIED, 0L),
                Map.entry(RESULT_TRX_LOCK_STRUCTS, 2L),
                Map.entry(RESULT_TRX_MYSQL_THREAD_ID, 45L),
                Map.entry(RESULT_PROCESS_ID, 45L),
                Map.entry(RESULT_PROCESS_USER, "ops002_user"),
                Map.entry(RESULT_PROCESS_HOST, "127.0.0.1:50000"),
                Map.entry(RESULT_PROCESS_DB, "ops002_test"),
                Map.entry(RESULT_REQUESTING_LOCK_ID, "421337:7:3:2"),
                Map.entry(RESULT_BLOCKING_LOCK_ID, "421336:7:3:2"),
                Map.entry(RESULT_BLOCKING_TRX_ID, "421336"),
                Map.entry(RESULT_BLOCKING_THREAD_ID, 44L),
                Map.entry(RESULT_BLOCKING_PROCESS_ID, 44L)
        ));

        ActiveTransaction transaction = MysqlActiveTransactionManager.readTransaction(
                resultSet,
                new MysqlActiveTransactionManager.LockMetadataQuery(
                        "SELECT ...", true, LockMetadataSource.MYSQL_80_PERFORMANCE_SCHEMA)
        );

        assertEquals("421337", transaction.getTrxId());
        assertEquals(45L, transaction.getThreadId());
        assertEquals(QueryState.UNAVAILABLE, transaction.getQueryState());
        assertEquals(LockMetadataState.AVAILABLE, transaction.getLockMetadataState());
        assertEquals(LockMetadataSource.MYSQL_80_PERFORMANCE_SCHEMA, transaction.getLockMetadataSource());
        assertTrue(transaction.getCanOpenSession());
        assertEquals("SELECT ID, USER, HOST, DB, COMMAND, TIME, STATE, INFO\n"
                + "FROM information_schema.PROCESSLIST\nWHERE ID = 45;",
                transaction.getConnectionInspectionSql());
        assertTrue(transaction.getLockWaitAvailable());
        assertEquals(44L, transaction.getBlockingThreadId());
        assertEquals("SELECT ID, USER, HOST, DB, COMMAND, TIME, STATE, INFO\n"
                + "FROM information_schema.PROCESSLIST\nWHERE ID = 44;",
                transaction.getBlockingConnectionInspectionSql());
        assertNull(transaction.getQuery());
    }

    @Test
    void unavailableConnectionsDoNotExposeInspectionSql() throws SQLException {
        ResultSet resultSet = resultSet(Map.ofEntries(
                Map.entry(RESULT_TRX_ID, "421338"),
                Map.entry(RESULT_TRX_STATE, "RUNNING"),
                Map.entry(RESULT_TRX_STARTED, Timestamp.valueOf("2026-08-31 12:00:00")),
                Map.entry(RESULT_TRX_MYSQL_THREAD_ID, 46L)
        ));

        ActiveTransaction transaction = MysqlActiveTransactionManager.readTransaction(
                resultSet,
                MysqlActiveTransactionManager.unavailableLockMetadataQuery()
        );

        assertFalse(transaction.getCanOpenSession());
        assertNull(transaction.getConnectionInspectionSql());
        assertFalse(transaction.getCanOpenBlockingSession());
        assertNull(transaction.getBlockingConnectionInspectionSql());
    }

    @Test
    void fallbackProcessPrivilegeFailureUsesSanitizedBusinessCode() {
        RuntimeException executionFailure = new IllegalStateException("execution failed",
                new SQLException("Access denied; you need (at least one of) the PROCESS privilege",
                        "42000", MYSQL_ERROR_SPECIFIC_ACCESS_DENIED));

        BusinessException exception = assertThrows(BusinessException.class, () -> {
            throw MysqlActiveTransactionManager.sanitizeActiveTransactionQueryException(executionFailure);
        });

        assertEquals(ERROR_CODE_PROCESS_PRIVILEGE_REQUIRED, exception.getCode());
        assertSame(executionFailure, exception.getCause());
    }

    @Test
    void managerDoesNotInlineSqlOrResultColumnNames() throws IOException {
        Path sourcePath = Path.of(
                System.getProperty("basedir"),
                "src/main/java/ai/chat2db/plugin/mysql/MysqlActiveTransactionManager.java"
        );
        String source = Files.readString(sourcePath);

        assertFalse(source.contains("resultSet.getString(\""));
        assertFalse(source.contains("resultSet.getTimestamp(\""));
        assertFalse(source.contains("nullableLong(resultSet, \""));
        assertFalse(source.contains("information_schema"));
        assertFalse(source.contains("performance_schema"));
    }

    @Test
    void nonProcessFallbackFailureRemainsOriginalRuntimeException() {
        RuntimeException executionFailure = new IllegalStateException("syntax error",
                new SQLException("Syntax error", "42000", 1064));

        assertSame(executionFailure,
                MysqlActiveTransactionManager.sanitizeActiveTransactionQueryException(executionFailure));
    }

    private static ResultSet resultSet(Map<String, Object> values) {
        AtomicBoolean wasNull = new AtomicBoolean();
        return (ResultSet) Proxy.newProxyInstance(
                MysqlActiveTransactionManagerTest.class.getClassLoader(),
                new Class<?>[]{ResultSet.class},
                (proxy, method, arguments) -> {
                    if ("wasNull".equals(method.getName())) {
                        return wasNull.get();
                    }
                    if (arguments != null && arguments.length == 1 && arguments[0] instanceof String column) {
                        Object value = values.get(column);
                        wasNull.set(value == null);
                        return switch (method.getName()) {
                            case "getString" -> value == null ? null : value.toString();
                            case "getTimestamp" -> value;
                            case "getLong" -> value == null ? 0L : ((Number) value).longValue();
                            default -> throw new UnsupportedOperationException(method.getName());
                        };
                    }
                    throw new UnsupportedOperationException(method.getName());
                }
        );
    }
}

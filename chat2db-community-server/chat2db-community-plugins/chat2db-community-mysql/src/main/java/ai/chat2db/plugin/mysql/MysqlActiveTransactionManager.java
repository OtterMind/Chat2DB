package ai.chat2db.plugin.mysql;

import ai.chat2db.community.domain.api.model.transaction.ActiveTransaction;
import ai.chat2db.community.domain.api.model.transaction.ActiveTransaction.LockMetadataSource;
import ai.chat2db.community.domain.api.model.transaction.ActiveTransaction.LockMetadataState;
import ai.chat2db.community.domain.api.model.transaction.ActiveTransaction.QueryState;
import ai.chat2db.community.domain.api.model.transaction.ActiveTransaction.SessionState;
import ai.chat2db.community.tools.exception.BusinessException;
import ai.chat2db.spi.DefaultSQLExecutor;
import ai.chat2db.spi.IActiveTransactionManager;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static ai.chat2db.plugin.mysql.constant.MysqlActiveTransactionConstants.*;

public class MysqlActiveTransactionManager implements IActiveTransactionManager {

    @Override
    public List<ActiveTransaction> activeTransactions(Connection connection) {
        LockMetadataQuery lockMetadataQuery = resolveLockMetadataQuery(connection);
        try {
            return executeActiveTransactions(connection, lockMetadataQuery);
        } catch (RuntimeException exception) {
            if (lockMetadataQuery.available() && hasMissingOrDeniedMetadataError(exception)) {
                return activeTransactionsWithoutLockMetadata(connection);
            }
            throw sanitizeActiveTransactionQueryException(exception);
        }
    }

    private List<ActiveTransaction> activeTransactionsWithoutLockMetadata(Connection connection) {
        try {
            return executeActiveTransactions(connection, unavailableLockMetadataQuery());
        } catch (RuntimeException exception) {
            throw sanitizeActiveTransactionQueryException(exception);
        }
    }

    private static List<ActiveTransaction> executeActiveTransactions(
            Connection connection,
            LockMetadataQuery lockMetadataQuery
    ) {
        return DefaultSQLExecutor.getInstance().execute(connection, lockMetadataQuery.sql(), resultSet -> {
            List<ActiveTransaction> transactions = new ArrayList<>();
            while (resultSet.next()) {
                transactions.add(readTransaction(resultSet, lockMetadataQuery));
            }
            return transactions;
        });
    }

    static ActiveTransaction readTransaction(ResultSet resultSet, LockMetadataQuery lockMetadataQuery)
            throws SQLException {
        ActiveTransaction transaction = new ActiveTransaction();
        transaction.setTrxId(resultSet.getString(RESULT_TRX_ID));
        transaction.setState(resultSet.getString(RESULT_TRX_STATE));
        transaction.setStartedAt(resultSet.getTimestamp(RESULT_TRX_STARTED));
        transaction.setAgeSeconds(nullableLong(resultSet, RESULT_TRX_AGE_SECONDS));
        transaction.setIsolationLevel(resultSet.getString(RESULT_TRX_ISOLATION_LEVEL));
        transaction.setRowsLocked(nullableLong(resultSet, RESULT_TRX_ROWS_LOCKED));
        transaction.setRowsModified(nullableLong(resultSet, RESULT_TRX_ROWS_MODIFIED));
        transaction.setLockStructs(nullableLong(resultSet, RESULT_TRX_LOCK_STRUCTS));
        Long threadId = nullableLong(resultSet, RESULT_TRX_MYSQL_THREAD_ID);
        transaction.setThreadId(threadId);
        transaction.setUser(resultSet.getString(RESULT_PROCESS_USER));
        transaction.setHost(resultSet.getString(RESULT_PROCESS_HOST));
        transaction.setDb(resultSet.getString(RESULT_PROCESS_DB));
        String query = resultSet.getString(RESULT_TRX_QUERY);
        transaction.setQuery(query);
        Long processId = nullableLong(resultSet, RESULT_PROCESS_ID);
        transaction.setSessionAvailable(processId != null);
        transaction.setSessionState(processId == null ? SessionState.DISAPPEARED_OR_HIDDEN : SessionState.LIVE);
        boolean canOpenSession = processId != null && threadId != null;
        transaction.setCanOpenSession(canOpenSession);
        transaction.setConnectionInspectionSql(canOpenSession ? connectionInspectionSql(threadId) : null);
        transaction.setQueryState(query == null ? QueryState.UNAVAILABLE : QueryState.VISIBLE);
        transaction.setLockMetadataState(lockMetadataQuery.available()
                ? LockMetadataState.AVAILABLE : LockMetadataState.UNAVAILABLE);
        transaction.setLockMetadataSource(lockMetadataQuery.source());
        putLockWait(transaction, resultSet);
        return transaction;
    }

    private static void putLockWait(ActiveTransaction transaction, ResultSet resultSet) throws SQLException {
        Long blockingThreadId = nullableLong(resultSet, RESULT_BLOCKING_THREAD_ID);
        Long blockingProcessId = nullableLong(resultSet, RESULT_BLOCKING_PROCESS_ID);
        String waitingLockId = resultSet.getString(RESULT_REQUESTING_LOCK_ID);
        String blockingLockId = resultSet.getString(RESULT_BLOCKING_LOCK_ID);
        transaction.setWaitingLockId(waitingLockId);
        transaction.setBlockingLockId(blockingLockId);
        transaction.setBlockingTrxId(resultSet.getString(RESULT_BLOCKING_TRX_ID));
        transaction.setWaitingPerformanceSchemaThreadId(nullableLong(resultSet, RESULT_REQUESTING_PS_THREAD_ID));
        transaction.setBlockingPerformanceSchemaThreadId(nullableLong(resultSet, RESULT_BLOCKING_PS_THREAD_ID));
        transaction.setBlockingThreadId(blockingThreadId);
        transaction.setBlockingSessionAvailable(blockingProcessId != null);
        boolean canOpenBlockingSession = blockingProcessId != null && blockingThreadId != null;
        transaction.setCanOpenBlockingSession(canOpenBlockingSession);
        transaction.setBlockingConnectionInspectionSql(
                canOpenBlockingSession ? connectionInspectionSql(blockingThreadId) : null);
        transaction.setBlockingUser(resultSet.getString(RESULT_BLOCKING_USER));
        transaction.setBlockingHost(resultSet.getString(RESULT_BLOCKING_HOST));
        transaction.setBlockingDb(resultSet.getString(RESULT_BLOCKING_DB));
        transaction.setWaitingObject(lockObject(resultSet, RESULT_WAITING_OBJECT_SCHEMA, RESULT_WAITING_OBJECT_NAME));
        transaction.setWaitingIndex(resultSet.getString(RESULT_WAITING_INDEX_NAME));
        transaction.setWaitingLockType(resultSet.getString(RESULT_WAITING_LOCK_TYPE));
        transaction.setWaitingLockMode(resultSet.getString(RESULT_WAITING_LOCK_MODE));
        transaction.setWaitingLockStatus(resultSet.getString(RESULT_WAITING_LOCK_STATUS));
        transaction.setWaitingLockData(resultSet.getString(RESULT_WAITING_LOCK_DATA));
        transaction.setBlockingObject(lockObject(
                resultSet,
                RESULT_BLOCKING_OBJECT_SCHEMA,
                RESULT_BLOCKING_OBJECT_NAME
        ));
        transaction.setBlockingIndex(resultSet.getString(RESULT_BLOCKING_INDEX_NAME));
        transaction.setBlockingLockType(resultSet.getString(RESULT_BLOCKING_LOCK_TYPE));
        transaction.setBlockingLockMode(resultSet.getString(RESULT_BLOCKING_LOCK_MODE));
        transaction.setBlockingLockStatus(resultSet.getString(RESULT_BLOCKING_LOCK_STATUS));
        transaction.setBlockingLockData(resultSet.getString(RESULT_BLOCKING_LOCK_DATA));
        transaction.setLockWaitAvailable(waitingLockId != null || blockingLockId != null);
    }

    private static String lockObject(ResultSet resultSet, String schemaColumn, String objectColumn) throws SQLException {
        String schema = resultSet.getString(schemaColumn);
        String object = resultSet.getString(objectColumn);
        if (schema == null || schema.isBlank()) {
            return object;
        }
        if (object == null || object.isBlank()) {
            return schema;
        }
        return schema + QUALIFIED_NAME_SEPARATOR + object;
    }

    private static Long nullableLong(ResultSet resultSet, String column) throws SQLException {
        long value = resultSet.getLong(column);
        return resultSet.wasNull() ? null : value;
    }

    static String connectionInspectionSql(Long connectionId) {
        return String.format(SQL_CONNECTION_INSPECTION, connectionId);
    }

    private static LockMetadataQuery resolveLockMetadataQuery(Connection connection) {
        try {
            executeProbe(connection, SQL_PROBE_MYSQL80_LOCKS);
            return new LockMetadataQuery(activeTransactionSql(LOCK_COLUMNS_80, LOCK_JOIN_80),
                    true, LockMetadataSource.MYSQL_80_PERFORMANCE_SCHEMA);
        } catch (RuntimeException exception) {
            if (!hasMissingOrDeniedMetadataError(exception)) {
                throw exception;
            }
        }

        try {
            executeProbe(connection, SQL_PROBE_MYSQL57_LOCKS);
            return new LockMetadataQuery(activeTransactionSql(LOCK_COLUMNS_57, LOCK_JOIN_57),
                    true, LockMetadataSource.MYSQL_57_INFORMATION_SCHEMA);
        } catch (RuntimeException exception) {
            if (!hasMissingOrDeniedMetadataError(exception)) {
                throw exception;
            }
            return unavailableLockMetadataQuery();
        }
    }

    private static void executeProbe(Connection connection, String sql) {
        DefaultSQLExecutor.getInstance().execute(connection, sql, resultSet -> null);
    }

    static String activeTransactionSql(String lockColumns, String lockJoin) {
        return String.format(SQL_ACTIVE_TRANSACTIONS, lockColumns, lockJoin);
    }

    static LockMetadataQuery unavailableLockMetadataQuery() {
        return new LockMetadataQuery(activeTransactionSql(LOCK_COLUMNS_UNAVAILABLE, LOCK_JOIN_UNAVAILABLE),
                false, null);
    }

    static boolean hasProcessPrivilegeError(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof SQLException sqlException
                    && (sqlException.getErrorCode() == MYSQL_ERROR_SPECIFIC_ACCESS_DENIED
                    || normalizedMessage(sqlException).contains(PROCESS_PRIVILEGE_MESSAGE_MARKER))) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    static boolean hasMissingOrDeniedMetadataError(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof SQLException sqlException) {
                String normalized = normalizedMessage(sqlException);
                if (LOCK_METADATA_UNAVAILABLE_ERROR_CODES.contains(sqlException.getErrorCode())
                        || LOCK_METADATA_UNAVAILABLE_MESSAGE_MARKERS.stream().anyMatch(normalized::contains)) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }

    static RuntimeException sanitizeActiveTransactionQueryException(RuntimeException exception) {
        if (hasProcessPrivilegeError(exception)) {
            return new BusinessException(ERROR_CODE_PROCESS_PRIVILEGE_REQUIRED, null, exception);
        }
        return exception;
    }

    private static String normalizedMessage(SQLException exception) {
        String message = exception.getMessage();
        return message == null ? EMPTY_ERROR_MESSAGE : message.toUpperCase(Locale.ROOT);
    }

    record LockMetadataQuery(String sql, boolean available, LockMetadataSource source) {
    }
}

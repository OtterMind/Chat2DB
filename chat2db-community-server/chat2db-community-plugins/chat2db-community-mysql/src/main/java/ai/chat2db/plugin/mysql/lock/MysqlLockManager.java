package ai.chat2db.plugin.mysql.lock;

import ai.chat2db.community.domain.api.model.lock.LockView;
import ai.chat2db.community.domain.api.model.lock.LockView.DataLock;
import ai.chat2db.community.domain.api.model.lock.LockView.ErrorCode;
import ai.chat2db.community.domain.api.model.lock.LockView.ErrorSection;
import ai.chat2db.community.domain.api.model.lock.LockView.LockKind;
import ai.chat2db.community.domain.api.model.lock.LockView.LockSession;
import ai.chat2db.community.domain.api.model.lock.LockView.LockWait;
import ai.chat2db.community.domain.api.model.lock.LockView.MetadataLock;
import ai.chat2db.community.domain.api.model.lock.LockView.Source;
import ai.chat2db.community.domain.api.model.lock.LockView.ViewError;
import ai.chat2db.community.domain.api.model.lock.LockView.WaitChain;
import ai.chat2db.spi.DefaultSQLExecutor;
import ai.chat2db.spi.ILockManager;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static ai.chat2db.plugin.mysql.constant.MysqlLockConstants.MYSQL_ERROR_COLUMN_COMMAND_DENIED;
import static ai.chat2db.plugin.mysql.constant.MysqlLockConstants.MYSQL_ERROR_COMMAND_DENIED;
import static ai.chat2db.plugin.mysql.constant.MysqlLockConstants.MYSQL_ERROR_DATABASE_ACCESS_DENIED;
import static ai.chat2db.plugin.mysql.constant.MysqlLockConstants.MYSQL_ERROR_SPECIFIC_ACCESS_DENIED;
import static ai.chat2db.plugin.mysql.constant.MysqlLockConstants.MYSQL_ERROR_TABLE_NOT_FOUND;
import static ai.chat2db.plugin.mysql.constant.MysqlLockConstants.SQL_DATA_LOCKS_57;
import static ai.chat2db.plugin.mysql.constant.MysqlLockConstants.SQL_DATA_LOCKS_80;
import static ai.chat2db.plugin.mysql.constant.MysqlLockConstants.SQL_DATA_LOCK_WAITS_57;
import static ai.chat2db.plugin.mysql.constant.MysqlLockConstants.SQL_DATA_LOCK_WAITS_80;
import static ai.chat2db.plugin.mysql.constant.MysqlLockConstants.SQL_METADATA_LOCKS;
import static ai.chat2db.plugin.mysql.constant.MysqlLockConstants.SQL_METADATA_LOCK_WAITS;
import static ai.chat2db.plugin.mysql.constant.MysqlLockConstants.SQL_PROBE_DATA_LOCKS_80;
import static ai.chat2db.plugin.mysql.constant.MysqlLockConstants.SQL_SESSION_INFO_57;
import static ai.chat2db.plugin.mysql.constant.MysqlLockConstants.SQL_SESSION_INFO_PERFORMANCE_SCHEMA;
import static ai.chat2db.plugin.mysql.constant.MysqlLockConstants.SQL_STATE_INVALID_AUTHORIZATION;
import static ai.chat2db.plugin.mysql.constant.MysqlLockConstants.SQL_STATE_TABLE_NOT_FOUND;

/** MySQL lock inspection for Performance Schema (8.0) and InnoDB (5.7). */
public class MysqlLockManager implements ILockManager {

    @Override
    public LockView lockView(Connection connection, Long dataSourceId) {
        List<ViewError> errors = new ArrayList<>();
        LockSourceProbe probe = probeLockSource(connection);
        boolean performanceSchema = probe.performanceSchema();

        List<DataLock> dataLocks;
        List<LockWait> waits;
        if (probe.failure() == null) {
            dataLocks = queryRows(connection, performanceSchema ? SQL_DATA_LOCKS_80 : SQL_DATA_LOCKS_57,
                    ErrorSection.DATA_LOCKS, errors,
                    resultSet -> readDataLock(resultSet, performanceSchema));
            waits = queryRows(connection, performanceSchema ? SQL_DATA_LOCK_WAITS_80 : SQL_DATA_LOCK_WAITS_57,
                    ErrorSection.WAITS, errors,
                    resultSet -> readLockWait(resultSet, performanceSchema));
        } else {
            errors.add(error(ErrorSection.DATA_LOCKS, probe.failure()));
            errors.add(error(ErrorSection.WAITS, probe.failure()));
            dataLocks = List.of();
            waits = List.of();
        }

        List<MetadataLock> metadataLocks = queryRows(connection, SQL_METADATA_LOCKS,
                ErrorSection.METADATA_LOCKS, errors, MysqlLockManager::readMetadataLock);
        List<MetadataWait> metadataWaits = queryRows(connection, SQL_METADATA_LOCK_WAITS,
                ErrorSection.METADATA_WAITS, errors, MysqlLockManager::readMetadataWait);
        List<LockSession> sessions = querySessionRows(connection, errors);
        enrichMetadataLocks(metadataLocks, sessions);

        LockView view = new LockView();
        view.setDataSourceId(dataSourceId);
        view.setSource(lockSource(performanceSchema, errors));
        view.setDataLocks(dataLocks);
        view.setWaits(waits);
        view.setMetaLocks(metadataLocks);
        view.setSessions(relevantSessions(sessions, dataLocks, waits, metadataLocks, metadataWaits));
        view.setWaitChains(buildWaitChains(waits, dataLocks, metadataLocks, sessions, dataSourceId));
        view.setMetadataWaitChains(buildMetadataWaitChains(metadataWaits, metadataLocks, sessions, dataSourceId));
        view.setErrors(errors);
        return view;
    }

    private static Source lockSource(boolean performanceSchema, List<ViewError> errors) {
        boolean lockTablesUnavailable = errors.stream()
                .map(ViewError::getSection)
                .anyMatch(section -> section == ErrorSection.DATA_LOCKS || section == ErrorSection.WAITS);
        if (lockTablesUnavailable) {
            return Source.UNAVAILABLE;
        }
        return performanceSchema ? Source.PERFORMANCE_SCHEMA : Source.INFORMATION_SCHEMA;
    }

    private static LockSourceProbe probeLockSource(Connection connection) {
        Integer databaseMajorVersion = databaseMajorVersion(connection);
        if (databaseMajorVersion != null && databaseMajorVersion < 8) {
            return new LockSourceProbe(false, null);
        }
        try {
            DefaultSQLExecutor.getInstance().execute(connection, SQL_PROBE_DATA_LOCKS_80,
                    resultSet -> Boolean.TRUE);
            return new LockSourceProbe(true, null);
        } catch (RuntimeException exception) {
            return shouldFallbackToLegacyLocks(exception)
                    ? new LockSourceProbe(false, null)
                    : new LockSourceProbe(true, exception);
        }
    }

    private static Integer databaseMajorVersion(Connection connection) {
        try {
            DatabaseMetaData metadata = connection.getMetaData();
            return metadata == null ? null : metadata.getDatabaseMajorVersion();
        } catch (SQLException | RuntimeException ignored) {
            return null;
        }
    }

    static boolean shouldFallbackToLegacyLocks(Throwable throwable) {
        Throwable cause = rootCause(throwable);
        if (cause instanceof SQLException sqlException
                && (sqlException.getErrorCode() == MYSQL_ERROR_TABLE_NOT_FOUND
                || SQL_STATE_TABLE_NOT_FOUND.equals(sqlException.getSQLState()))) {
            return true;
        }
        String message = cause.getMessage();
        return message != null && (message.toLowerCase().contains("unknown table")
                || message.toLowerCase().contains("doesn't exist")
                || message.toLowerCase().contains("does not exist"));
    }

    private static <T> List<T> queryRows(Connection connection, String sql, ErrorSection section,
            List<ViewError> errors, RowMapper<T> mapper) {
        try {
            return queryRows(connection, sql, mapper);
        } catch (RuntimeException exception) {
            errors.add(error(section, exception));
            return List.of();
        }
    }

    private static <T> List<T> queryRows(Connection connection, String sql, RowMapper<T> mapper) {
        return DefaultSQLExecutor.getInstance().execute(connection, sql, resultSet -> {
            List<T> rows = new ArrayList<>();
            while (resultSet.next()) {
                rows.add(mapper.map(resultSet));
            }
            return rows;
        });
    }

    private static ViewError error(ErrorSection section, RuntimeException exception) {
        ViewError error = new ViewError();
        error.setSection(section);
        error.setCode(isPrivilegeError(rootCause(exception)) ? ErrorCode.PRIVILEGE_REQUIRED : ErrorCode.UNAVAILABLE);
        return error;
    }

    private static List<LockSession> querySessionRows(Connection connection, List<ViewError> errors) {
        try {
            return queryRows(connection, SQL_SESSION_INFO_PERFORMANCE_SCHEMA,
                    MysqlLockManager::readPerformanceSchemaSession);
        } catch (RuntimeException performanceSchemaException) {
            try {
                return queryRows(connection, SQL_SESSION_INFO_57, MysqlLockManager::readLegacySession);
            } catch (RuntimeException informationSchemaException) {
                errors.add(error(ErrorSection.SESSIONS, informationSchemaException));
                return List.of();
            }
        }
    }

    private static DataLock readDataLock(ResultSet resultSet, boolean performanceSchema) throws SQLException {
        DataLock lock = new DataLock();
        if (performanceSchema) {
            lock.setLockId(value(resultSet, "ENGINE_LOCK_ID"));
            lock.setTransactionId(value(resultSet, "ENGINE_TRANSACTION_ID"));
            lock.setEngineThreadId(value(resultSet, "THREAD_ID"));
            lock.setEventId(value(resultSet, "EVENT_ID"));
            lock.setObjectSchema(value(resultSet, "OBJECT_SCHEMA"));
            lock.setObjectName(value(resultSet, "OBJECT_NAME"));
            lock.setIndexName(value(resultSet, "INDEX_NAME"));
            lock.setLockType(value(resultSet, "LOCK_TYPE"));
            lock.setLockMode(value(resultSet, "LOCK_MODE"));
            lock.setLockStatus(value(resultSet, "LOCK_STATUS"));
            lock.setLockData(value(resultSet, "LOCK_DATA"));
        } else {
            lock.setLockId(value(resultSet, "lock_id"));
            lock.setTransactionId(value(resultSet, "lock_trx_id"));
            lock.setObjectName(value(resultSet, "lock_table"));
            lock.setIndexName(value(resultSet, "lock_index"));
            lock.setLockType(value(resultSet, "lock_type"));
            lock.setLockMode(value(resultSet, "lock_mode"));
            lock.setLockData(value(resultSet, "lock_data"));
            lock.setSpaceId(value(resultSet, "lock_space"));
            lock.setPageId(value(resultSet, "lock_page"));
            lock.setRecordId(value(resultSet, "lock_rec"));
        }
        return lock;
    }

    private static LockWait readLockWait(ResultSet resultSet, boolean performanceSchema) throws SQLException {
        LockWait wait = new LockWait();
        if (performanceSchema) {
            wait.setWaiterLockId(value(resultSet, "REQUESTING_ENGINE_LOCK_ID"));
            wait.setWaiterTransactionId(value(resultSet, "REQUESTING_ENGINE_TRANSACTION_ID"));
            wait.setWaiterThreadId(value(resultSet, "REQUESTING_THREAD_ID"));
            wait.setWaiterEventId(value(resultSet, "REQUESTING_EVENT_ID"));
            wait.setBlockerLockId(value(resultSet, "BLOCKING_ENGINE_LOCK_ID"));
            wait.setBlockerTransactionId(value(resultSet, "BLOCKING_ENGINE_TRANSACTION_ID"));
            wait.setBlockerThreadId(value(resultSet, "BLOCKING_THREAD_ID"));
            wait.setBlockerEventId(value(resultSet, "BLOCKING_EVENT_ID"));
        } else {
            wait.setWaiterTransactionId(value(resultSet, "requesting_trx_id"));
            wait.setWaiterLockId(value(resultSet, "requested_lock_id"));
            wait.setBlockerTransactionId(value(resultSet, "blocking_trx_id"));
            wait.setBlockerLockId(value(resultSet, "blocking_lock_id"));
        }
        return wait;
    }

    private static MetadataLock readMetadataLock(ResultSet resultSet) throws SQLException {
        MetadataLock lock = new MetadataLock();
        lock.setObjectType(value(resultSet, "OBJECT_TYPE"));
        lock.setObjectSchema(value(resultSet, "OBJECT_SCHEMA"));
        lock.setObjectName(value(resultSet, "OBJECT_NAME"));
        lock.setObjectInstanceId(value(resultSet, "OBJECT_INSTANCE_BEGIN"));
        lock.setLockType(value(resultSet, "LOCK_TYPE"));
        lock.setLockDuration(value(resultSet, "LOCK_DURATION"));
        lock.setLockStatus(value(resultSet, "LOCK_STATUS"));
        lock.setOwnerThreadId(value(resultSet, "OWNER_THREAD_ID"));
        lock.setOwnerEventId(value(resultSet, "OWNER_EVENT_ID"));
        return lock;
    }

    private static MetadataWait readMetadataWait(ResultSet resultSet) throws SQLException {
        return new MetadataWait(
                value(resultSet, "OBJECT_SCHEMA"),
                value(resultSet, "OBJECT_NAME"),
                value(resultSet, "WAITING_THREAD_ID"),
                value(resultSet, "WAITING_PID"),
                value(resultSet, "WAITING_ACCOUNT"),
                value(resultSet, "WAITING_LOCK_TYPE"),
                value(resultSet, "WAITING_QUERY"),
                value(resultSet, "BLOCKING_THREAD_ID"),
                value(resultSet, "BLOCKING_PID"),
                value(resultSet, "BLOCKING_ACCOUNT"),
                value(resultSet, "BLOCKING_LOCK_TYPE"));
    }

    private static LockSession readPerformanceSchemaSession(ResultSet resultSet) throws SQLException {
        LockSession session = new LockSession();
        session.setEngineThreadId(value(resultSet, "THREAD_ID"));
        session.setSessionId(first(value(resultSet, "PROCESSLIST_ID"), value(resultSet, "trx_mysql_thread_id")));
        session.setUser(value(resultSet, "PROCESSLIST_USER"));
        session.setHost(value(resultSet, "PROCESSLIST_HOST"));
        session.setDatabaseName(value(resultSet, "PROCESSLIST_DB"));
        session.setCommand(value(resultSet, "PROCESSLIST_COMMAND"));
        session.setTimeSeconds(value(resultSet, "PROCESSLIST_TIME"));
        session.setState(first(value(resultSet, "trx_state"), value(resultSet, "PROCESSLIST_STATE")));
        session.setQuery(first(value(resultSet, "trx_query"), value(resultSet, "PROCESSLIST_INFO")));
        session.setTransactionId(value(resultSet, "trx_id"));
        return session;
    }

    private static LockSession readLegacySession(ResultSet resultSet) throws SQLException {
        LockSession session = new LockSession();
        session.setSessionId(value(resultSet, "trx_mysql_thread_id"));
        session.setUser(value(resultSet, "USER"));
        session.setHost(value(resultSet, "HOST"));
        session.setDatabaseName(value(resultSet, "DB"));
        session.setState(value(resultSet, "trx_state"));
        session.setQuery(value(resultSet, "trx_query"));
        session.setTransactionId(value(resultSet, "trx_id"));
        return session;
    }

    private static List<LockSession> relevantSessions(List<LockSession> sessions, List<DataLock> dataLocks,
            List<LockWait> waits, List<MetadataLock> metadataLocks, List<MetadataWait> metadataWaits) {
        Set<String> transactionIds = new HashSet<>();
        Set<String> threadIds = new HashSet<>();
        for (DataLock lock : dataLocks) {
            addIdentifier(transactionIds, lock.getTransactionId());
            addIdentifier(threadIds, lock.getEngineThreadId());
        }
        for (LockWait wait : waits) {
            addIdentifier(transactionIds, wait.getWaiterTransactionId());
            addIdentifier(transactionIds, wait.getBlockerTransactionId());
            addIdentifier(threadIds, wait.getWaiterThreadId());
            addIdentifier(threadIds, wait.getBlockerThreadId());
        }
        for (MetadataLock lock : metadataLocks) {
            addIdentifier(threadIds, lock.getOwnerThreadId());
        }
        for (MetadataWait wait : metadataWaits) {
            addIdentifier(threadIds, wait.waiterThreadId());
            addIdentifier(threadIds, wait.blockerThreadId());
        }
        return sessions.stream()
                .filter(session -> transactionIds.contains(session.getTransactionId())
                        || threadIds.contains(session.getEngineThreadId()))
                .toList();
    }

    private static void addIdentifier(Set<String> identifiers, String value) {
        if (value != null) {
            identifiers.add(value);
        }
    }

    private static List<WaitChain> buildWaitChains(List<LockWait> waits, List<DataLock> dataLocks,
            List<MetadataLock> metadataLocks, List<LockSession> sessions, Long dataSourceId) {
        SessionIndex sessionIndex = new SessionIndex(sessions);
        Map<String, DataLock> dataLocksById = new HashMap<>();
        for (DataLock lock : dataLocks) {
            if (lock.getLockId() != null) {
                dataLocksById.put(lock.getLockId(), lock);
            }
        }
        Map<String, Integer> metadataLockCountsByThread = metadataLockCountsByThread(metadataLocks);
        List<WaitEdge> edges = new ArrayList<>();
        Map<String, Set<String>> outgoing = new HashMap<>();
        for (LockWait wait : waits) {
            WaitEdge edge = waitEdge(wait, dataLocksById);
            if (edge == null) {
                continue;
            }
            edges.add(edge);
            outgoing.computeIfAbsent(edge.waiterKey(), ignored -> new LinkedHashSet<>()).add(edge.blockerKey());
        }

        List<WaitChain> chains = new ArrayList<>();
        for (WaitEdge edge : edges) {
            LockSession waiter = sessionIndex.find(edge.waiterTransactionId(), edge.waiterThreadId());
            LockSession blocker = sessionIndex.find(edge.blockerTransactionId(), edge.blockerThreadId());
            DataLock waiterLock = dataLocksById.get(edge.waiterLockId());
            DataLock blockerLock = dataLocksById.get(edge.blockerLockId());
            boolean cycle = reaches(edge.blockerKey(), edge.waiterKey(), outgoing, new HashSet<>());

            WaitChain chain = new WaitChain();
            chain.setDataSourceId(dataSourceId);
            chain.setLockKind(LockKind.DATA);
            chain.setLockObject(dataLockObject(waiterLock));
            chain.setWaiterTransactionId(edge.waiterTransactionId());
            chain.setWaiterLockId(edge.waiterLockId());
            chain.setWaiterThreadId(displayThreadId(waiter, edge.waiterThreadId()));
            chain.setWaiterEngineThreadId(edge.waiterThreadId());
            chain.setWaiterState(waiter == null ? null : waiter.getState());
            chain.setWaiterUser(waiter == null ? null : waiter.getUser());
            chain.setWaiterHost(waiter == null ? null : waiter.getHost());
            chain.setWaiterDatabase(waiter == null ? null : waiter.getDatabaseName());
            chain.setWaiterQuery(waiter == null ? null : waiter.getQuery());
            chain.setWaiterSessionAvailable(waiter != null);
            chain.setWaiterMetadataLockCount(metadataLockCount(waiter, edge.waiterThreadId(),
                    metadataLockCountsByThread));
            chain.setWaiterLockMode(waiterLock == null ? null : waiterLock.getLockMode());
            chain.setBlockerTransactionId(edge.blockerTransactionId());
            chain.setBlockerLockId(edge.blockerLockId());
            chain.setBlockerThreadId(displayThreadId(blocker, edge.blockerThreadId()));
            chain.setBlockerEngineThreadId(edge.blockerThreadId());
            chain.setBlockerState(blocker == null ? null : blocker.getState());
            chain.setBlockerUser(blocker == null ? null : blocker.getUser());
            chain.setBlockerHost(blocker == null ? null : blocker.getHost());
            chain.setBlockerDatabase(blocker == null ? null : blocker.getDatabaseName());
            chain.setBlockerQuery(blocker == null ? null : blocker.getQuery());
            chain.setBlockerSessionAvailable(blocker != null);
            chain.setBlockerMetadataLockCount(metadataLockCount(blocker, edge.blockerThreadId(),
                    metadataLockCountsByThread));
            chain.setBlockerLockMode(blockerLock == null ? null : blockerLock.getLockMode());
            chain.setRootBlocker(!cycle && !outgoing.containsKey(edge.blockerKey()));
            chain.setCycle(cycle);
            chains.add(chain);
        }
        return chains;
    }

    private static List<WaitChain> buildMetadataWaitChains(List<MetadataWait> metadataWaits,
            List<MetadataLock> metadataLocks, List<LockSession> sessions, Long dataSourceId) {
        SessionIndex sessionIndex = new SessionIndex(sessions);
        Map<String, Integer> metadataLockCountsByThread = metadataLockCountsByThread(metadataLocks);
        Map<String, Set<String>> outgoing = new HashMap<>();
        for (MetadataWait wait : metadataWaits) {
            if (validMetadataEdge(wait)) {
                outgoing.computeIfAbsent(wait.waiterThreadId(), ignored -> new LinkedHashSet<>())
                        .add(wait.blockerThreadId());
            }
        }

        List<WaitChain> chains = new ArrayList<>();
        for (MetadataWait wait : metadataWaits) {
            if (!validMetadataEdge(wait)) {
                continue;
            }
            LockSession waiter = sessionIndex.find(null, wait.waiterThreadId());
            LockSession blocker = sessionIndex.find(null, wait.blockerThreadId());
            String object = qualifiedObject(wait.objectSchema(), wait.objectName());
            boolean cycle = reaches(wait.blockerThreadId(), wait.waiterThreadId(), outgoing, new HashSet<>());

            WaitChain chain = new WaitChain();
            chain.setDataSourceId(dataSourceId);
            chain.setLockKind(LockKind.METADATA);
            chain.setLockObject(object);
            chain.setWaiterLockId(metadataLockId(object, wait.waiterThreadId()));
            chain.setWaiterThreadId(first(wait.waiterSessionId(), displayThreadId(waiter, wait.waiterThreadId())));
            chain.setWaiterEngineThreadId(wait.waiterThreadId());
            chain.setWaiterState(waiter == null ? null : waiter.getState());
            chain.setWaiterUser(first(waiter == null ? null : waiter.getUser(), wait.waiterAccount()));
            chain.setWaiterHost(waiter == null ? null : waiter.getHost());
            chain.setWaiterDatabase(first(waiter == null ? null : waiter.getDatabaseName(), wait.objectSchema()));
            chain.setWaiterQuery(first(wait.waiterQuery(), waiter == null ? null : waiter.getQuery()));
            chain.setWaiterSessionAvailable(wait.waiterSessionId() != null || waiter != null);
            chain.setWaiterMetadataLockCount(metadataLockCount(waiter, wait.waiterThreadId(),
                    metadataLockCountsByThread));
            chain.setWaiterLockMode(wait.waiterLockMode());
            chain.setBlockerLockId(metadataLockId(object, wait.blockerThreadId()));
            chain.setBlockerThreadId(first(wait.blockerSessionId(), displayThreadId(blocker, wait.blockerThreadId())));
            chain.setBlockerEngineThreadId(wait.blockerThreadId());
            chain.setBlockerState(blocker == null ? null : blocker.getState());
            chain.setBlockerUser(first(blocker == null ? null : blocker.getUser(), wait.blockerAccount()));
            chain.setBlockerHost(blocker == null ? null : blocker.getHost());
            chain.setBlockerDatabase(first(blocker == null ? null : blocker.getDatabaseName(), wait.objectSchema()));
            chain.setBlockerQuery(blocker == null ? null : blocker.getQuery());
            chain.setBlockerSessionAvailable(wait.blockerSessionId() != null || blocker != null);
            chain.setBlockerMetadataLockCount(metadataLockCount(blocker, wait.blockerThreadId(),
                    metadataLockCountsByThread));
            chain.setBlockerLockMode(wait.blockerLockMode());
            chain.setRootBlocker(!cycle && !outgoing.containsKey(wait.blockerThreadId()));
            chain.setCycle(cycle);
            chains.add(chain);
        }
        return chains;
    }

    private static boolean validMetadataEdge(MetadataWait wait) {
        return wait.waiterThreadId() != null && wait.blockerThreadId() != null
                && !wait.waiterThreadId().equals(wait.blockerThreadId());
    }

    private static WaitEdge waitEdge(LockWait wait, Map<String, DataLock> dataLocksById) {
        DataLock waiterLock = dataLocksById.get(wait.getWaiterLockId());
        DataLock blockerLock = dataLocksById.get(wait.getBlockerLockId());
        String waiterTransactionId = first(wait.getWaiterTransactionId(),
                waiterLock == null ? null : waiterLock.getTransactionId());
        String blockerTransactionId = first(wait.getBlockerTransactionId(),
                blockerLock == null ? null : blockerLock.getTransactionId());
        String waiterThreadId = first(wait.getWaiterThreadId(),
                waiterLock == null ? null : waiterLock.getEngineThreadId());
        String blockerThreadId = first(wait.getBlockerThreadId(),
                blockerLock == null ? null : blockerLock.getEngineThreadId());
        String waiterKey = identityKey(waiterTransactionId, waiterThreadId, wait.getWaiterLockId());
        String blockerKey = identityKey(blockerTransactionId, blockerThreadId, wait.getBlockerLockId());
        if (waiterKey == null || blockerKey == null) {
            return null;
        }
        return new WaitEdge(waiterKey, blockerKey, waiterTransactionId, blockerTransactionId,
                waiterThreadId, blockerThreadId, wait.getWaiterLockId(), wait.getBlockerLockId());
    }

    private static String dataLockObject(DataLock lock) {
        return lock == null ? null : qualifiedObject(lock.getObjectSchema(), lock.getObjectName());
    }

    private static String qualifiedObject(String schema, String name) {
        if (schema == null) {
            return name;
        }
        return name == null ? schema : schema + "." + name;
    }

    private static String metadataLockId(String object, String threadId) {
        return "metadata:" + (object == null ? "unknown" : object) + ":" + threadId;
    }

    private static Map<String, Integer> metadataLockCountsByThread(List<MetadataLock> metadataLocks) {
        Map<String, Integer> counts = new HashMap<>();
        for (MetadataLock lock : metadataLocks) {
            if (lock.getOwnerThreadId() != null) {
                counts.merge(lock.getOwnerThreadId(), 1, Integer::sum);
            }
        }
        return counts;
    }

    private static int metadataLockCount(LockSession session, String fallbackThreadId,
            Map<String, Integer> metadataLockCountsByThread) {
        String threadId = session == null ? fallbackThreadId : first(session.getEngineThreadId(), fallbackThreadId);
        return threadId == null ? 0 : metadataLockCountsByThread.getOrDefault(threadId, 0);
    }

    private static void enrichMetadataLocks(List<MetadataLock> metadataLocks, List<LockSession> sessions) {
        SessionIndex sessionIndex = new SessionIndex(sessions);
        for (MetadataLock lock : metadataLocks) {
            LockSession session = sessionIndex.find(null, lock.getOwnerThreadId());
            lock.setOwnerSessionAvailable(session != null);
            if (session != null) {
                lock.setOwnerSessionId(session.getSessionId());
                lock.setOwnerUser(session.getUser());
                lock.setOwnerHost(session.getHost());
                lock.setOwnerDatabase(session.getDatabaseName());
                lock.setOwnerState(session.getState());
                lock.setOwnerQuery(session.getQuery());
            }
        }
    }

    private static boolean reaches(String from, String target, Map<String, Set<String>> outgoing, Set<String> seen) {
        if (from.equals(target)) {
            return true;
        }
        if (!seen.add(from)) {
            return false;
        }
        for (String next : outgoing.getOrDefault(from, Set.of())) {
            if (reaches(next, target, outgoing, seen)) {
                return true;
            }
        }
        return false;
    }

    private static String displayThreadId(LockSession session, String fallbackThreadId) {
        return session == null ? fallbackThreadId : first(session.getSessionId(), fallbackThreadId);
    }

    private static String identityKey(String transactionId, String threadId, String lockId) {
        if (transactionId != null) {
            return "trx:" + transactionId;
        }
        if (threadId != null) {
            return "thread:" + threadId;
        }
        return lockId == null ? null : "lock:" + lockId;
    }

    private static String first(String... values) {
        for (String value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static String value(ResultSet resultSet, String column) throws SQLException {
        String value = resultSet.getString(column);
        return value == null || value.isBlank() || "null".equalsIgnoreCase(value) ? null : value;
    }

    private static Throwable rootCause(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static boolean isPrivilegeError(Throwable throwable) {
        if (throwable instanceof SQLException sqlException) {
            int errorCode = sqlException.getErrorCode();
            if (errorCode == MYSQL_ERROR_DATABASE_ACCESS_DENIED
                    || errorCode == MYSQL_ERROR_COMMAND_DENIED
                    || errorCode == MYSQL_ERROR_COLUMN_COMMAND_DENIED
                    || errorCode == MYSQL_ERROR_SPECIFIC_ACCESS_DENIED
                    || SQL_STATE_INVALID_AUTHORIZATION.equals(sqlException.getSQLState())) {
                return true;
            }
        }
        String message = throwable.getMessage();
        if (message == null) {
            return false;
        }
        String lower = message.toLowerCase();
        return lower.contains("denied") || lower.contains("permission") || lower.contains("privilege");
    }

    @FunctionalInterface
    private interface RowMapper<T> {
        T map(ResultSet resultSet) throws SQLException;
    }

    private record LockSourceProbe(boolean performanceSchema, RuntimeException failure) {
    }

    private record MetadataWait(String objectSchema, String objectName, String waiterThreadId,
            String waiterSessionId, String waiterAccount, String waiterLockMode, String waiterQuery,
            String blockerThreadId, String blockerSessionId, String blockerAccount, String blockerLockMode) {
    }

    private record WaitEdge(String waiterKey, String blockerKey, String waiterTransactionId,
            String blockerTransactionId, String waiterThreadId, String blockerThreadId,
            String waiterLockId, String blockerLockId) {
    }

    private static final class SessionIndex {
        private final Map<String, LockSession> byTransaction = new HashMap<>();
        private final Map<String, LockSession> byEngineThread = new HashMap<>();
        private final Map<String, LockSession> bySession = new HashMap<>();

        private SessionIndex(List<LockSession> sessions) {
            for (LockSession session : sessions) {
                put(byTransaction, session.getTransactionId(), session);
                put(byEngineThread, session.getEngineThreadId(), session);
                put(bySession, session.getSessionId(), session);
            }
        }

        private LockSession find(String transactionId, String threadId) {
            LockSession session = transactionId == null ? null : byTransaction.get(transactionId);
            if (session == null && threadId != null) {
                session = byEngineThread.get(threadId);
            }
            if (session == null && threadId != null) {
                session = bySession.get(threadId);
            }
            return session;
        }

        private static void put(Map<String, LockSession> index, String key, LockSession row) {
            if (key != null) {
                index.put(key, row);
            }
        }
    }
}

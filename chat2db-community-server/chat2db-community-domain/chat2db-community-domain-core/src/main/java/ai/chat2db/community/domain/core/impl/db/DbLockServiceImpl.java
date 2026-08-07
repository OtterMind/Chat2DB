package ai.chat2db.community.domain.core.impl.db;

import ai.chat2db.community.domain.api.service.db.IDbLockService;
import ai.chat2db.spi.DefaultSQLExecutor;
import ai.chat2db.spi.sql.Chat2DBContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lock inspection using Performance Schema on MySQL 8.0 and the legacy InnoDB
 * information_schema tables on 5.7, plus metadata locks when instrumented.
 */
@Slf4j
@Service
public class DbLockServiceImpl implements IDbLockService {

    private static final String SQL_DATA_LOCKS_8 =
            "SELECT ENGINE_LOCK_ID, ENGINE_TRANSACTION_ID, THREAD_ID, OBJECT_SCHEMA, OBJECT_NAME, "
                    + "INDEX_NAME, LOCK_TYPE, LOCK_MODE, LOCK_STATUS, LOCK_DATA "
                    + "FROM performance_schema.data_locks ORDER BY ENGINE_LOCK_ID";
    private static final String SQL_DATA_LOCK_WAITS_8 =
            "SELECT REQUESTING_ENGINE_LOCK_ID, REQUESTING_ENGINE_TRANSACTION_ID, "
                    + "BLOCKING_ENGINE_LOCK_ID, BLOCKING_ENGINE_TRANSACTION_ID "
                    + "FROM performance_schema.data_lock_waits";
    private static final String SQL_DATA_LOCKS_57 =
            "SELECT lock_id, lock_trx_id, lock_mode, lock_type, lock_table, lock_index, "
                    + "lock_space, lock_page, lock_rec, lock_data "
                    + "FROM information_schema.innodb_locks ORDER BY lock_id";
    private static final String SQL_DATA_LOCK_WAITS_57 =
            "SELECT requesting_trx_id, requested_lock_id, blocking_trx_id, blocking_lock_id "
                    + "FROM information_schema.innodb_lock_waits";
    private static final String SQL_METADATA_LOCKS =
            "SELECT OBJECT_SCHEMA, OBJECT_NAME, LOCK_TYPE, LOCK_DURATION, OWNER_THREAD_ID, OWNER_EVENT_ID "
                    + "FROM performance_schema.metadata_locks ORDER BY OBJECT_SCHEMA, OBJECT_NAME";
    private static final String SQL_SESSION_INFO =
            "SELECT t.trx_id, t.trx_mysql_thread_id, t.trx_state, p.USER, p.HOST, p.DB, t.trx_query "
                    + "FROM information_schema.innodb_trx t "
                    + "LEFT JOIN information_schema.processlist p ON t.trx_mysql_thread_id = p.ID";

    @Override
    public Map<String, Object> lockView() {
        Connection connection = Chat2DBContext.getConnection();
        boolean ps = performanceSchemaLocksAvailable(connection);

        List<Map<String, Object>> dataLocks = ps ? queryRows(connection, SQL_DATA_LOCKS_8) : queryRows(connection, SQL_DATA_LOCKS_57);
        List<Map<String, Object>> waits = ps ? queryRows(connection, SQL_DATA_LOCK_WAITS_8) : queryRows(connection, SQL_DATA_LOCK_WAITS_57);
        List<Map<String, Object>> metaLocks = queryMetaLocks(connection);
        List<Map<String, Object>> sessions = queryRows(connection, SQL_SESSION_INFO);
        List<Map<String, Object>> waitChains = buildWaitChains(waits, sessions, ps);

        Map<String, Object> view = new LinkedHashMap<>();
        view.put("source", ps ? "performance_schema" : "information_schema");
        view.put("dataLocks", dataLocks);
        view.put("waits", waits);
        view.put("metaLocks", metaLocks);
        view.put("waitChains", waitChains);
        return view;
    }

    /**
     * Detects whether performance_schema.data_locks is queryable (MySQL 8.0 with the
     * data_locks instrumentation enabled); 5.7 falls back to innodb_locks.
     */
    private static boolean performanceSchemaLocksAvailable(Connection connection) {
        try {
            return DefaultSQLExecutor.getInstance().execute(connection, "SELECT 1 FROM performance_schema.data_locks LIMIT 1",
                    resultSet -> Boolean.TRUE);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static List<Map<String, Object>> queryMetaLocks(Connection connection) {
        try {
            return queryRows(connection, SQL_METADATA_LOCKS);
        } catch (RuntimeException e) {
            // metadata_locks instrumentation may be disabled or absent (5.7 without the
            // performance_schema table); an empty list marks the source unavailable.
            return List.of();
        }
    }

    private static List<Map<String, Object>> queryRows(Connection connection, String sql) {
        return DefaultSQLExecutor.getInstance().execute(connection, sql, resultSet -> {
            List<Map<String, Object>> rows = new ArrayList<>();
            while (resultSet.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                for (int i = 1; i <= resultSet.getMetaData().getColumnCount(); i++) {
                    String label = resultSet.getMetaData().getColumnLabel(i);
                    Object value = resultSet.getObject(i);
                    row.put(label, value == null ? null : String.valueOf(value));
                }
                rows.add(row);
            }
            return rows;
        });
    }

    /**
     * Builds waiter -> blocker pairs, resolving users/hosts/queries from the transaction
     * session map and flagging the root blocker (the blocker is not itself waiting).
     * Rows whose lock or session disappeared mid-refresh are skipped rather than failing.
     */
    private static List<Map<String, Object>> buildWaitChains(List<Map<String, Object>> waits,
                                                             List<Map<String, Object>> sessions, boolean ps) {
        Map<String, Map<String, Object>> byTrx = new HashMap<>();
        for (Map<String, Object> session : sessions) {
            byTrx.put(String.valueOf(session.get("trx_id")), session);
        }
        Map<String, Map<String, Object>> byLock = new HashMap<>();
        for (Map<String, Object> lock : waits) {
            String waiterLock = String.valueOf(lock.get(ps ? "REQUESTING_ENGINE_LOCK_ID" : "requested_lock_id"));
            byLock.put(waiterLock, lock);
        }

        List<Map<String, Object>> chains = new ArrayList<>();
        for (Map<String, Object> wait : waits) {
            String waiterLockId = String.valueOf(wait.get(ps ? "REQUESTING_ENGINE_LOCK_ID" : "requested_lock_id"));
            String blockerLockId = String.valueOf(wait.get(ps ? "BLOCKING_ENGINE_LOCK_ID" : "blocking_lock_id"));
            String waiterTrx = String.valueOf(wait.get(ps ? "REQUESTING_ENGINE_TRANSACTION_ID" : "requesting_trx_id"));
            String blockerTrx = String.valueOf(wait.get(ps ? "BLOCKING_ENGINE_TRANSACTION_ID" : "blocking_trx_id"));
            if ("null".equals(waiterTrx) || "null".equals(blockerTrx)) {
                continue;
            }
            Map<String, Object> waiter = byTrx.get(waiterTrx);
            Map<String, Object> blocker = byTrx.get(blockerTrx);
            if (waiter == null || blocker == null) {
                continue;
            }
            // A blocker is the root blocker when its lock never appears as a waiter lock.
            boolean blockerIsRoot = !byLock.containsKey(blockerLockId);
            Map<String, Object> chain = new LinkedHashMap<>();
            chain.put("waiterThreadId", waiter.get("trx_mysql_thread_id"));
            chain.put("waiterState", waiter.get("trx_state"));
            chain.put("waiterUser", waiter.get("USER"));
            chain.put("waiterHost", waiter.get("HOST"));
            chain.put("waiterQuery", waiter.get("trx_query"));
            chain.put("blockerThreadId", blocker.get("trx_mysql_thread_id"));
            chain.put("blockerState", blocker.get("trx_state"));
            chain.put("blockerUser", blocker.get("USER"));
            chain.put("blockerHost", blocker.get("HOST"));
            chain.put("blockerQuery", blocker.get("trx_query"));
            chain.put("rootBlocker", blockerIsRoot);
            chains.add(chain);
        }
        return chains;
    }
}

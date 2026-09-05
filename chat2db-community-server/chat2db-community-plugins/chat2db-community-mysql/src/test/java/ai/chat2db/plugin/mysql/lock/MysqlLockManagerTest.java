package ai.chat2db.plugin.mysql.lock;

import ai.chat2db.community.domain.api.model.lock.LockView;
import ai.chat2db.community.domain.api.model.lock.LockView.DataLock;
import ai.chat2db.community.domain.api.model.lock.LockView.ErrorCode;
import ai.chat2db.community.domain.api.model.lock.LockView.ErrorSection;
import ai.chat2db.community.domain.api.model.lock.LockView.LockKind;
import ai.chat2db.community.domain.api.model.lock.LockView.LockSession;
import ai.chat2db.community.domain.api.model.lock.LockView.MetadataLock;
import ai.chat2db.community.domain.api.model.lock.LockView.Source;
import ai.chat2db.community.domain.api.model.lock.LockView.WaitChain;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MysqlLockManagerTest {

    @Test
    void returnsDeterministicEmptyViewWhenLockTablesAreUnavailable() {
        Map<String, SQLException> sqlFailures = Map.of(
                "performance_schema.data_locks", new SQLException("SELECT command denied"),
                "information_schema.innodb_locks", new SQLException("Access denied"),
                "information_schema.innodb_lock_waits", new SQLException("Access denied"),
                "performance_schema.metadata_locks", new SQLException("Access denied"),
                "sys.schema_table_lock_waits", new SQLException("Access denied"),
                "information_schema.innodb_trx", new SQLException("Access denied")
        );

        LockView view = new MysqlLockManager().lockView(connection(sqlFailures, Map.of()), 21L);

        assertEquals(21L, view.getDataSourceId());
        assertEquals(Source.UNAVAILABLE, view.getSource());
        assertEquals(List.of(), view.getDataLocks());
        assertEquals(List.of(), view.getWaits());
        assertEquals(List.of(), view.getMetaLocks());
        assertEquals(List.of(), view.getWaitChains());
        assertEquals(List.of(), view.getMetadataWaitChains());
        assertEquals(List.of(ErrorSection.DATA_LOCKS, ErrorSection.WAITS,
                        ErrorSection.METADATA_LOCKS, ErrorSection.METADATA_WAITS, ErrorSection.SESSIONS),
                view.getErrors().stream().map(LockView.ViewError::getSection).toList());
        assertTrue(view.getErrors().stream().allMatch(error -> error.getCode() == ErrorCode.PRIVILEGE_REQUIRED));
    }

    @Test
    void fallsBackOnlyWhenPerformanceSchemaLockTableIsMissing() {
        assertTrue(MysqlLockManager.shouldFallbackToLegacyLocks(
                new SQLException("Unknown table 'performance_schema.data_locks'", "42S02", 1146)));
        assertFalse(MysqlLockManager.shouldFallbackToLegacyLocks(
                new SQLException("SELECT command denied for data_locks", "42000", 1142)));
    }

    @Test
    void usesLegacySourceForMysql57BeforeProbingTheMysql80LockTable() {
        Map<String, SQLException> failures = Map.of(
                "performance_schema.data_locks", new SQLException("SELECT command denied", "42000", 1142));

        LockView view = new MysqlLockManager().lockView(connection(failures, Map.of(), 5), 23L);

        assertEquals(Source.INFORMATION_SCHEMA, view.getSource());
        assertTrue(view.getErrors().stream().noneMatch(error -> error.getSection() == ErrorSection.DATA_LOCKS
                || error.getSection() == ErrorSection.WAITS));
    }

    @Test
    void reportsSyntaxFailuresAsUnavailableInsteadOfPrivilegeErrors() {
        Map<String, SQLException> failures = Map.of(
                "performance_schema.data_locks",
                new SQLException("You have an error in your SQL syntax", "42000", 1064));

        LockView view = new MysqlLockManager().lockView(connection(failures, Map.of()), 22L);

        assertEquals(List.of(ErrorCode.UNAVAILABLE, ErrorCode.UNAVAILABLE), view.getErrors().stream()
                .filter(error -> error.getSection() == ErrorSection.DATA_LOCKS
                        || error.getSection() == ErrorSection.WAITS)
                .map(LockView.ViewError::getCode)
                .toList());
    }

    @Test
    void buildsTypedDataAndMetadataWaitGraphsAndFiltersUnrelatedSessions() {
        Map<String, List<Map<String, Object>>> sqlRows = Map.of(
                "performance_schema.data_locks", List.of(
                        row("ENGINE_LOCK_ID", "l-100", "ENGINE_TRANSACTION_ID", "100", "THREAD_ID", "10",
                                "EVENT_ID", "1", "OBJECT_SCHEMA", "app", "OBJECT_NAME", "orders",
                                "INDEX_NAME", "PRIMARY", "LOCK_TYPE", "RECORD", "LOCK_MODE", "X",
                                "LOCK_STATUS", "GRANTED", "LOCK_DATA", "1"),
                        row("ENGINE_LOCK_ID", "l-200", "ENGINE_TRANSACTION_ID", "200", "THREAD_ID", "20",
                                "OBJECT_SCHEMA", "app", "OBJECT_NAME", "orders", "LOCK_MODE", "X"),
                        row("ENGINE_LOCK_ID", "l-300", "ENGINE_TRANSACTION_ID", "300", "THREAD_ID", "30"),
                        row("ENGINE_LOCK_ID", "l-400", "ENGINE_TRANSACTION_ID", "400", "THREAD_ID", "40"),
                        row("ENGINE_LOCK_ID", "l-500", "ENGINE_TRANSACTION_ID", "500", "THREAD_ID", "50"),
                        row("ENGINE_LOCK_ID", "l-600", "ENGINE_TRANSACTION_ID", "600", "THREAD_ID", "60"),
                        row("ENGINE_LOCK_ID", "l-800", "ENGINE_TRANSACTION_ID", "800", "THREAD_ID", "80"),
                        row("ENGINE_LOCK_ID", "l-900", "ENGINE_TRANSACTION_ID", "900", "THREAD_ID", "90")
                ),
                "performance_schema.data_lock_waits", List.of(
                        wait("l-100", "100", "10", "l-200", "200", "20"),
                        wait("l-100", "100", "10", "l-300", "300", "30"),
                        wait("l-200", "200", "20", "l-400", "400", "40"),
                        wait("l-500", "500", "50", "l-600", "600", "60"),
                        wait("l-600", "600", "60", "l-500", "500", "50"),
                        wait("l-800", "800", "80", "l-900", "900", "90")
                ),
                "performance_schema.metadata_locks", List.of(
                        row("OBJECT_TYPE", "TABLE", "OBJECT_SCHEMA", "app", "OBJECT_NAME", "orders",
                                "OBJECT_INSTANCE_BEGIN", "1001", "LOCK_TYPE", "SHARED_READ",
                                "LOCK_DURATION", "TRANSACTION", "LOCK_STATUS", "GRANTED", "OWNER_THREAD_ID", "30"),
                        row("OBJECT_TYPE", "TABLE", "OBJECT_SCHEMA", "app", "OBJECT_NAME", "customers",
                                "OBJECT_INSTANCE_BEGIN", "1002", "LOCK_TYPE", "EXCLUSIVE",
                                "LOCK_DURATION", "TRANSACTION", "LOCK_STATUS", "PENDING", "OWNER_THREAD_ID", "40")
                ),
                "sys.schema_table_lock_waits", List.of(
                        row("OBJECT_SCHEMA", "app", "OBJECT_NAME", "customers",
                                "WAITING_THREAD_ID", "40", "WAITING_PID", "104",
                                "WAITING_ACCOUNT", "dave@client-d", "WAITING_LOCK_TYPE", "EXCLUSIVE",
                                "WAITING_LOCK_DURATION", "TRANSACTION", "WAITING_QUERY", "alter table customers",
                                "BLOCKING_THREAD_ID", "30", "BLOCKING_PID", "103",
                                "BLOCKING_ACCOUNT", "carol@client-c", "BLOCKING_LOCK_TYPE", "SHARED_READ",
                                "BLOCKING_LOCK_DURATION", "TRANSACTION"),
                        row("OBJECT_SCHEMA", "app", "OBJECT_NAME", "customers",
                                "WAITING_THREAD_ID", "40", "WAITING_PID", "104",
                                "WAITING_ACCOUNT", "dave@client-d", "WAITING_LOCK_TYPE", "EXCLUSIVE",
                                "WAITING_LOCK_DURATION", "TRANSACTION", "WAITING_QUERY", "alter table customers",
                                "BLOCKING_THREAD_ID", "40", "BLOCKING_PID", "104",
                                "BLOCKING_ACCOUNT", "dave@client-d", "BLOCKING_LOCK_TYPE", "SHARED_UPGRADABLE",
                                "BLOCKING_LOCK_DURATION", "TRANSACTION")
                ),
                "performance_schema.threads", List.of(
                        session("10", "101", "100", "alice", "client-a", "app", "LOCK WAIT", "wait 100"),
                        session("20", "102", "200", "bob", "client-b", "app", "LOCK WAIT", "wait 200"),
                        session("30", "103", "300", "carol", "client-c", "app", "executing", "update root a"),
                        session("40", "104", "400", "dave", "client-d", "app", "executing", "update root b"),
                        session("50", "105", "500", "erin", "client-e", "app", "LOCK WAIT", "cycle a"),
                        session("60", "106", "600", "frank", "client-f", "app", "LOCK WAIT", "cycle b"),
                        session("80", "108", "800", "gina", "client-g", "app", "LOCK WAIT", "wait stale"),
                        session("999", "1999", "1999", "unrelated", "client-z", "other", "executing",
                                "select secret from unrelated_table")
                )
        );

        LockView view = new MysqlLockManager().lockView(connection(Map.of(), sqlRows), 41L);

        assertEquals(Source.PERFORMANCE_SCHEMA, view.getSource());
        assertEquals(8, view.getDataLocks().size());
        assertEquals(7, view.getSessions().size());
        assertTrue(view.getSessions().stream().map(LockSession::getUser).noneMatch("unrelated"::equals));
        DataLock dataLock = view.getDataLocks().get(0);
        assertEquals("app", dataLock.getObjectSchema());
        assertEquals("orders", dataLock.getObjectName());
        assertEquals("PRIMARY", dataLock.getIndexName());
        assertEquals("RECORD", dataLock.getLockType());
        assertEquals("X", dataLock.getLockMode());
        assertEquals("GRANTED", dataLock.getLockStatus());
        assertEquals("1", dataLock.getLockData());

        List<WaitChain> chains = view.getWaitChains();
        assertEquals(6, chains.size());
        WaitChain firstHop = chain(chains, "100", "200");
        assertNotNull(firstHop);
        assertEquals(LockKind.DATA, firstHop.getLockKind());
        assertEquals("app.orders", firstHop.getLockObject());
        assertEquals("X", firstHop.getWaiterLockMode());
        assertEquals("X", firstHop.getBlockerLockMode());
        assertFalse(firstHop.isRootBlocker());
        assertEquals("102", firstHop.getBlockerThreadId());
        assertTrue(chain(chains, "100", "300").isRootBlocker());
        assertEquals(1, chain(chains, "100", "300").getBlockerMetadataLockCount());
        assertTrue(chain(chains, "200", "400").isRootBlocker());
        assertTrue(chain(chains, "500", "600").isCycle());
        WaitChain stale = chain(chains, "800", "900");
        assertFalse(stale.isBlockerSessionAvailable());
        assertEquals("90", stale.getBlockerThreadId());

        assertEquals(List.of("GRANTED", "PENDING"), view.getMetaLocks().stream()
                .map(MetadataLock::getLockStatus)
                .toList());
        assertEquals(List.of("1001", "1002"), view.getMetaLocks().stream()
                .map(MetadataLock::getObjectInstanceId)
                .toList());
        assertEquals(1, view.getMetadataWaitChains().size());
        WaitChain metadataChain = view.getMetadataWaitChains().get(0);
        assertEquals(LockKind.METADATA, metadataChain.getLockKind());
        assertEquals("app.customers", metadataChain.getLockObject());
        assertEquals("104", metadataChain.getWaiterThreadId());
        assertEquals("103", metadataChain.getBlockerThreadId());
        assertEquals("alter table customers", metadataChain.getWaiterQuery());
        assertEquals("update root a", metadataChain.getBlockerQuery());
        assertEquals("EXCLUSIVE", metadataChain.getWaiterLockMode());
        assertEquals("SHARED_READ", metadataChain.getBlockerLockMode());
        assertTrue(metadataChain.isRootBlocker());
    }

    @Test
    void fallsBackToLegacyInnoDbWaitsWhileKeepingThreadMetadataCorrelation() {
        Map<String, SQLException> sqlFailures =
                Map.of("SELECT 1 FROM performance_schema.data_locks", new SQLException("Unknown table"));
        Map<String, List<Map<String, Object>>> sqlRows = Map.of(
                "information_schema.innodb_locks", List.of(
                        row("lock_id", "legacy-waiter", "lock_trx_id", "trx-a", "lock_mode", "X",
                                "lock_type", "RECORD", "lock_table", "`legacy`.`orders`",
                                "lock_index", "PRIMARY", "lock_space", "7", "lock_page", "8",
                                "lock_rec", "9", "lock_data", "1"),
                        row("lock_id", "legacy-blocker", "lock_trx_id", "trx-b")
                ),
                "information_schema.innodb_lock_waits", List.of(
                        row("requesting_trx_id", "trx-a", "requested_lock_id", "legacy-waiter",
                                "blocking_trx_id", "trx-b", "blocking_lock_id", "legacy-blocker")
                ),
                "performance_schema.metadata_locks", List.of(
                        row("OBJECT_TYPE", "TABLE", "OBJECT_SCHEMA", "legacy", "OBJECT_NAME", "orders",
                                "OBJECT_INSTANCE_BEGIN", "2001", "LOCK_TYPE", "EXCLUSIVE",
                                "LOCK_DURATION", "TRANSACTION", "OWNER_THREAD_ID", "70")
                ),
                "performance_schema.threads", List.of(
                        session("60", "601", "trx-a", "waiter", "host-a", "legacy", "LOCK WAIT", "wait legacy"),
                        session("70", "701", "trx-b", "blocker", "host-b", "legacy", "executing", "root legacy")
                )
        );

        LockView view = new MysqlLockManager().lockView(connection(sqlFailures, sqlRows), 42L);

        assertEquals(Source.INFORMATION_SCHEMA, view.getSource());
        DataLock dataLock = view.getDataLocks().get(0);
        assertEquals("`legacy`.`orders`", dataLock.getObjectName());
        assertEquals("PRIMARY", dataLock.getIndexName());
        assertEquals("7", dataLock.getSpaceId());
        assertEquals("8", dataLock.getPageId());
        assertEquals("9", dataLock.getRecordId());
        WaitChain waitChain = view.getWaitChains().get(0);
        assertEquals("701", waitChain.getBlockerThreadId());
        assertEquals("blocker", waitChain.getBlockerUser());
        assertEquals(1, waitChain.getBlockerMetadataLockCount());
        assertTrue(waitChain.isRootBlocker());
        assertTrue(view.getErrors().isEmpty());
        assertEquals("701", view.getMetaLocks().get(0).getOwnerSessionId());
    }

    private static Map<String, Object> wait(String waiterLockId, String waiterTransactionId,
            String waiterThreadId, String blockerLockId, String blockerTransactionId, String blockerThreadId) {
        return row("REQUESTING_ENGINE_LOCK_ID", waiterLockId,
                "REQUESTING_ENGINE_TRANSACTION_ID", waiterTransactionId,
                "REQUESTING_THREAD_ID", waiterThreadId,
                "REQUESTING_EVENT_ID", "1",
                "BLOCKING_ENGINE_LOCK_ID", blockerLockId,
                "BLOCKING_ENGINE_TRANSACTION_ID", blockerTransactionId,
                "BLOCKING_THREAD_ID", blockerThreadId,
                "BLOCKING_EVENT_ID", "2");
    }

    private static WaitChain chain(List<WaitChain> chains, String waiterTransactionId,
            String blockerTransactionId) {
        return chains.stream()
                .filter(row -> waiterTransactionId.equals(row.getWaiterTransactionId())
                        && blockerTransactionId.equals(row.getBlockerTransactionId()))
                .findFirst()
                .orElse(null);
    }

    private static Map<String, Object> session(String threadId, String processlistId, String transactionId,
            String user, String host, String database, String state, String query) {
        return row("THREAD_ID", threadId, "PROCESSLIST_ID", processlistId, "PROCESSLIST_USER", user,
                "PROCESSLIST_HOST", host, "PROCESSLIST_DB", database, "PROCESSLIST_COMMAND", "Query",
                "PROCESSLIST_TIME", "1", "PROCESSLIST_STATE", state, "PROCESSLIST_INFO", query,
                "trx_id", transactionId, "trx_mysql_thread_id", processlistId, "trx_state", state,
                "trx_query", query);
    }

    private static Map<String, Object> row(Object... values) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int index = 0; index < values.length; index += 2) {
            row.put((String) values[index], values[index + 1]);
        }
        return row;
    }

    private static Connection connection(Map<String, SQLException> sqlFailures,
            Map<String, List<Map<String, Object>>> sqlRows) {
        return connection(sqlFailures, sqlRows, null);
    }

    private static Connection connection(Map<String, SQLException> sqlFailures,
            Map<String, List<Map<String, Object>>> sqlRows, Integer databaseMajorVersion) {
        return proxy(Connection.class, (proxy, method, args) -> switch (method.getName()) {
            case "isClosed" -> false;
            case "getMetaData" -> databaseMajorVersion == null ? null : databaseMetaData(databaseMajorVersion);
            case "prepareStatement" -> statement((String) args[0], sqlFailures, sqlRows);
            case "close" -> null;
            default -> defaultValue(method.getReturnType());
        });
    }

    private static DatabaseMetaData databaseMetaData(int databaseMajorVersion) {
        return proxy(DatabaseMetaData.class, (proxy, method, args) ->
                "getDatabaseMajorVersion".equals(method.getName())
                        ? databaseMajorVersion
                        : defaultValue(method.getReturnType()));
    }

    private static PreparedStatement statement(String sql, Map<String, SQLException> sqlFailures,
            Map<String, List<Map<String, Object>>> sqlRows) {
        ResultSet[] resultSet = new ResultSet[1];
        return proxy(PreparedStatement.class, (proxy, method, args) -> switch (method.getName()) {
            case "execute" -> {
                resultSet[0] = resultSet(sql, sqlFailures, sqlRows);
                yield true;
            }
            case "getResultSet" -> resultSet[0];
            case "close" -> null;
            default -> defaultValue(method.getReturnType());
        });
    }

    private static ResultSet resultSet(String sql, Map<String, SQLException> sqlFailures,
            Map<String, List<Map<String, Object>>> sqlRows) throws SQLException {
        for (Map.Entry<String, SQLException> entry : sqlFailures.entrySet()) {
            if (sql.contains(entry.getKey())) {
                throw entry.getValue();
            }
        }
        List<Map<String, Object>> rows = rowsForSql(sql, sqlRows);
        Set<String> selectedColumns = selectedColumns(sql);
        int[] current = {-1};
        return proxy(ResultSet.class, (proxy, method, args) -> switch (method.getName()) {
            case "next" -> ++current[0] < rows.size();
            case "getString" -> {
                String column = String.valueOf(args[0]);
                if (!selectedColumns.contains(column)) {
                    throw new SQLException("Column was not selected: " + column);
                }
                Object value = rows.get(current[0]).get(column);
                yield value == null ? null : String.valueOf(value);
            }
            case "close" -> null;
            default -> defaultValue(method.getReturnType());
        });
    }

    private static List<Map<String, Object>> rowsForSql(String sql,
            Map<String, List<Map<String, Object>>> sqlRows) {
        return sqlRows.entrySet().stream()
                .filter(entry -> sql.contains(entry.getKey()))
                .findFirst()
                .map(Map.Entry::getValue)
                .orElse(List.of());
    }

    private static Set<String> selectedColumns(String sql) {
        String upperSql = sql.toUpperCase();
        int projectionStart = upperSql.indexOf("SELECT ") + "SELECT ".length();
        int projectionEnd = upperSql.indexOf(" FROM ", projectionStart);
        return Arrays.stream(sql.substring(projectionStart, projectionEnd).split(","))
                .map(String::trim)
                .map(MysqlLockManagerTest::columnLabel)
                .collect(Collectors.toSet());
    }

    private static String columnLabel(String expression) {
        int aliasIndex = expression.toUpperCase().lastIndexOf(" AS ");
        if (aliasIndex >= 0) {
            return expression.substring(aliasIndex + " AS ".length()).trim();
        }
        int qualifierIndex = expression.lastIndexOf('.');
        return expression.substring(qualifierIndex + 1).trim();
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(MysqlLockManagerTest.class.getClassLoader(),
                new Class<?>[]{type}, handler);
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == char.class) {
            return '\0';
        }
        return 0;
    }
}

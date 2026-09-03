package ai.chat2db.plugin.mysql;

import ai.chat2db.community.domain.api.model.db.DbSessionKillResult;
import ai.chat2db.community.tools.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MysqlSessionManagerTest {

    private final MysqlSessionManager manager = new MysqlSessionManager();

    @Test
    void supportsMysql57AndMysql80() {
        assertFalse(MysqlSessionManager.supportsSessionInspection("5.6.51"));
        assertTrue(MysqlSessionManager.supportsSessionInspection("5.7.44"));
        assertTrue(MysqlSessionManager.supportsSessionInspection("8.0.36"));
    }

    @Test
    void matchesSessionsOwnedByCurrentDatabaseUser() {
        assertTrue(MysqlSessionManager.sameMysqlUser("chat2db@localhost", "chat2db"));
        assertTrue(MysqlSessionManager.sameMysqlUser("CHAT2DB@10.0.0.1", "chat2db@%"));
        assertFalse(MysqlSessionManager.sameMysqlUser("admin@localhost", "chat2db"));
        assertFalse(MysqlSessionManager.sameMysqlUser(null, "chat2db"));
    }

    @Test
    void grantsConnectionAdminAndSuperCanKillOtherUserSessions() {
        assertTrue(MysqlSessionManager.grantAllowsOtherUserSessionKill(
                "GRANT SELECT, PROCESS, CONNECTION_ADMIN ON *.* TO `ops001_admin`@`%`"));
        assertTrue(MysqlSessionManager.grantAllowsOtherUserSessionKill(
                "GRANT SUPER ON *.* TO 'ops001_admin'@'%'"));
        assertTrue(MysqlSessionManager.grantAllowsOtherUserSessionKill(
                "GRANT ALL PRIVILEGES ON *.* TO 'root'@'localhost' WITH GRANT OPTION"));
    }

    @Test
    void processAndDatabaseScopedGrantsDoNotKillOtherUserSessions() {
        assertFalse(MysqlSessionManager.grantAllowsOtherUserSessionKill(
                "GRANT PROCESS ON *.* TO 'ops001_reader'@'%'"));
        assertFalse(MysqlSessionManager.grantAllowsOtherUserSessionKill(
                "GRANT ALL PRIVILEGES ON `app`.* TO 'app_admin'@'%'"));
        assertFalse(MysqlSessionManager.grantAllowsOtherUserSessionKill(
                "GRANT SERVICE_CONNECTION_ADMIN ON *.* TO 'router'@'%'"));
    }

    @Test
    void listMarksTheCurrentConnectionId() {
        Connection connection = connection(Map.of(
                "SELECT CONNECTION_ID()", rows(Map.of("CONNECTION_ID()", 55L)),
                "SELECT ID, USER, HOST, DB, COMMAND, TIME, STATE, INFO FROM information_schema.processlist ORDER BY ID",
                rows(
                        Map.of("ID", 55L, "USER", "chat2db", "HOST", "127.0.0.1:61000", "DB", "app",
                                "COMMAND", "Query", "TIME", 1L, "STATE", "executing", "INFO", "select 1"),
                        Map.of("ID", 56L, "USER", "other", "HOST", "127.0.0.1:61001", "DB", "app",
                                "COMMAND", "Sleep", "TIME", 30L, "STATE", "", "INFO", "")
                )
        ));

        List<Map<String, Object>> sessions = manager.list(connection, "8.0.36");

        assertEquals(true, sessions.get(0).get("current"));
        assertEquals(false, sessions.get(1).get("current"));
    }

    @Test
    void rejectsUnsupportedVersionBeforeQueryingJdbc() {
        BusinessException exception = assertThrows(BusinessException.class,
                () -> manager.list(null, "5.6.51"));

        assertEquals("mysql.session.unsupported", exception.getCode());
    }

    @Test
    void killMissingTargetReturnsAlreadyFinishedWithoutExecutingKill() {
        AtomicReference<String> executedSql = new AtomicReference<>();
        Connection connection = connection(Map.of(
                "SELECT CONNECTION_ID()", rows(Map.of("CONNECTION_ID()", 55L)),
                "SELECT USER FROM information_schema.processlist WHERE ID = ?", rows()
        ), executedSql);

        DbSessionKillResult result = manager.kill(connection, "8.0.36", "chat2db", 56L, "QUERY");

        assertEquals("ALREADY_FINISHED", result.getStatus());
        assertEquals(56L, result.getConnectionId());
        assertEquals("KILL QUERY 56", result.getSql());
        assertEquals(null, executedSql.get());
    }

    @Test
    void killUnknownThreadErrorReturnsAlreadyFinishedOutcome() {
        AtomicReference<String> executedSql = new AtomicReference<>();
        Connection connection = connection(Map.of(
                "SELECT CONNECTION_ID()", rows(Map.of("CONNECTION_ID()", 55L)),
                "SELECT USER FROM information_schema.processlist WHERE ID = ?",
                rows(Map.of("USER", "chat2db"))
        ), executedSql, new SQLException("Unknown thread id: 56", "HY000", 1094));

        DbSessionKillResult result = manager.kill(connection, "8.0.36", "chat2db", 56L, "CONNECTION");

        assertEquals("ALREADY_FINISHED", result.getStatus());
        assertEquals(56L, result.getConnectionId());
        assertEquals("KILL CONNECTION 56", result.getSql());
        assertEquals("KILL CONNECTION ? [56]", executedSql.get());
    }

    @Test
    void killPermissionErrorRemainsFailure() {
        Connection connection = connection(Map.of(
                "SELECT CONNECTION_ID()", rows(Map.of("CONNECTION_ID()", 55L)),
                "SELECT USER FROM information_schema.processlist WHERE ID = ?",
                rows(Map.of("USER", "chat2db"))
        ), new AtomicReference<>(), new SQLException("Access denied; you need CONNECTION_ADMIN", "42000", 1227));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> manager.kill(connection, "8.0.36", "chat2db", 56L, "QUERY"));

        assertEquals("mysql.session.killFailed", exception.getCode());
    }

    private static Connection connection(Map<String, ResultSet> resultSets) {
        return connection(resultSets, new AtomicReference<>());
    }

    private static Connection connection(Map<String, ResultSet> resultSets, AtomicReference<String> executedSql) {
        return connection(resultSets, executedSql, null);
    }

    private static Connection connection(Map<String, ResultSet> resultSets, AtomicReference<String> executedSql,
                                         SQLException killException) {
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "prepareStatement" -> preparedStatement((String) args[0], resultSets, executedSql,
                            killException);
                    case "createStatement" -> statement(executedSql, killException);
                    case "isClosed" -> false;
                    case "isValid" -> true;
                    case "close" -> null;
                    case "toString" -> "MysqlSessionManagerTestConnection";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> defaultValue(method.getReturnType());
                });
    }

    private static Statement statement(AtomicReference<String> executedSql, SQLException killException) {
        return (Statement) Proxy.newProxyInstance(Statement.class.getClassLoader(), new Class<?>[]{Statement.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "execute" -> {
                        executedSql.set((String) args[0]);
                        if (killException != null) {
                            throw killException;
                        }
                        yield false;
                    }
                    case "close" -> null;
                    case "toString" -> "MysqlSessionManagerTestStatement";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> defaultValue(method.getReturnType());
                });
    }

    private static PreparedStatement preparedStatement(String sql, Map<String, ResultSet> resultSets,
                                                       AtomicReference<String> executedSql,
                                                       SQLException killException) {
        long[] boundConnectionId = {0L};
        return (PreparedStatement) Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(),
                new Class<?>[]{PreparedStatement.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "execute" -> {
                        if (sql.startsWith("KILL")) {
                            executedSql.set(sql + " [" + boundConnectionId[0] + "]");
                            if (killException != null) {
                                throw killException;
                            }
                        }
                        yield resultSets.containsKey(sql);
                    }
                    case "executeQuery" -> resultSets.get(sql);
                    case "getResultSet" -> resultSets.get(sql);
                    case "setLong" -> {
                        boundConnectionId[0] = (Long) args[1];
                        yield null;
                    }
                    case "close" -> null;
                    case "toString" -> sql;
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> defaultValue(method.getReturnType());
                });
    }

    @SafeVarargs
    private static ResultSet rows(Map<String, Object>... rows) {
        return resultSet(List.of(rows));
    }

    private static ResultSet resultSet(List<Map<String, Object>> rows) {
        int[] index = {-1};
        return (ResultSet) Proxy.newProxyInstance(ResultSet.class.getClassLoader(), new Class<?>[]{ResultSet.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "next" -> ++index[0] < rows.size();
                    case "getLong" -> ((Number) rows.get(index[0]).get((String) args[0])).longValue();
                    case "getString" -> {
                        Object value = rows.get(index[0]).get((String) args[0]);
                        yield value == null ? null : String.valueOf(value);
                    }
                    case "close" -> null;
                    case "toString" -> rows.toString();
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> defaultValue(method.getReturnType());
                });
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

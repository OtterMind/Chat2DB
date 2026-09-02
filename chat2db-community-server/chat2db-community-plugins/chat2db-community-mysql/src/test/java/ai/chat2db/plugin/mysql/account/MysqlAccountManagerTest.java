package ai.chat2db.plugin.mysql.account;

import ai.chat2db.community.domain.api.enums.plugin.AccountActionTypeEnum;
import ai.chat2db.community.domain.api.model.account.AccountExecuteResponse;
import ai.chat2db.community.domain.api.model.account.AccountInfo;
import ai.chat2db.community.domain.api.model.account.AccountManagerCapability;
import ai.chat2db.community.domain.api.model.account.AccountOperationRequest;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class MysqlAccountManagerTest {

    @Test
    void listAccountsReadsPasswordExpirationAndResourceLimitColumns() {
        QueryConnection connection = new QueryConnection();
        connection.whenQuery(
                "SELECT User, Host, plugin, account_locked, password_expired, password_last_changed, password_lifetime,"
                        + " max_questions, max_updates, max_connections, max_user_connections FROM mysql.user ORDER BY User, Host",
                List.of(row(
                        "User", "app",
                        "Host", "%",
                        "plugin", "caching_sha2_password",
                        "account_locked", "Y",
                        "password_expired", "N",
                        "password_last_changed", "2026-08-30 10:15:00",
                        "password_lifetime", 90,
                        "max_questions", 100,
                        "max_updates", 20,
                        "max_connections", 10,
                        "max_user_connections", 3
                ))
        );

        AccountInfo account = new MysqlAccountManager().listAccounts(connection.proxy()).get(0);

        assertEquals("app@%", account.getDisplayName());
        assertEquals(Boolean.TRUE, account.getLocked());
        assertEquals(Boolean.FALSE, account.getPasswordExpired());
        assertEquals("INTERVAL", account.getPasswordExpirePolicy());
        assertEquals("2026-08-30 10:15:00", account.getPasswordLastChanged());
        assertEquals(90, account.getPasswordLifetime());
        assertEquals(100, account.getMaxQueriesPerHour());
        assertEquals(20, account.getMaxUpdatesPerHour());
        assertEquals(10, account.getMaxConnectionsPerHour());
        assertEquals(3, account.getMaxUserConnections());
    }

    @Test
    void listAccountsDerivesEveryPasswordExpirationReadbackPolicy() {
        QueryConnection connection = new QueryConnection();
        connection.whenQuery(
                "SELECT User, Host, plugin, account_locked, password_expired, password_last_changed, password_lifetime,"
                        + " max_questions, max_updates, max_connections, max_user_connections FROM mysql.user ORDER BY User, Host",
                List.of(
                        row("User", "default_policy", "Host", "%", "password_expired", "N", "password_lifetime", null),
                        row("User", "never_policy", "Host", "%", "password_expired", "N", "password_lifetime", 0),
                        row("User", "interval_policy", "Host", "%", "password_expired", "N", "password_lifetime", 30),
                        row("User", "immediate_policy", "Host", "%", "password_expired", "Y", "password_lifetime", null)
                )
        );

        List<AccountInfo> accounts = new MysqlAccountManager().listAccounts(connection.proxy());

        assertEquals("DEFAULT", accounts.get(0).getPasswordExpirePolicy());
        assertEquals("NEVER", accounts.get(1).getPasswordExpirePolicy());
        assertEquals("INTERVAL", accounts.get(2).getPasswordExpirePolicy());
        assertEquals("IMMEDIATE", accounts.get(3).getPasswordExpirePolicy());
    }

    @Test
    void listAccountsDoesNotSelectSensitiveAuthenticationHashes() {
        QueryConnection connection = new QueryConnection();
        connection.whenQuery(
                "SELECT User, Host, plugin, account_locked, password_expired, password_last_changed, password_lifetime,"
                        + " max_questions, max_updates, max_connections, max_user_connections FROM mysql.user ORDER BY User, Host",
                List.of(row("User", "app", "Host", "%"))
        );

        new MysqlAccountManager().listAccounts(connection.proxy());

        assertEquals(List.of(
                "SELECT User, Host, plugin, account_locked, password_expired, password_last_changed, password_lifetime,"
                        + " max_questions, max_updates, max_connections, max_user_connections FROM mysql.user ORDER BY User, Host"
        ), connection.executedSql());
    }

    @Test
    void capabilityReflectsReadablePasswordAndResourceColumns() {
        QueryConnection connection = new QueryConnection();
        connection.whenQuery("SELECT User, Host FROM mysql.user LIMIT 1", List.of(row("User", "app", "Host", "%")));
        connection.whenQuery("SELECT account_locked FROM mysql.user LIMIT 1", List.of(row("account_locked", "N")));
        connection.whenQuery("SELECT password_expired, password_last_changed, password_lifetime FROM mysql.user LIMIT 1",
                List.of(row("password_expired", "N")));
        connection.whenQuery("SELECT max_questions, max_updates, max_connections, max_user_connections FROM mysql.user LIMIT 1",
                List.of(row("max_questions", 0)));
        connection.whenQuery("SELECT CURRENT_USER()", List.of(row("CURRENT_USER()", "root@localhost")));
        connection.metadata("MySQL", "8.0.39", 8);

        AccountManagerCapability capability = new MysqlAccountManager().capability(connection.proxy());

        assertEquals(Boolean.TRUE, capability.getAccountListReadable());
        assertEquals(Boolean.TRUE, capability.getAccountLockSupported());
        assertEquals(Boolean.TRUE, capability.getPasswordExpirationSupported());
        assertEquals(Boolean.TRUE, capability.getResourceLimitsSupported());
        assertEquals(Boolean.TRUE, capability.getRoleManagementSupported());
    }

    @Test
    void capabilityDisablesSettingsWhenColumnReadPrivilegeIsMissing() {
        QueryConnection connection = new QueryConnection();
        connection.whenQuery("SELECT User, Host FROM mysql.user LIMIT 1", List.of(row("User", "app", "Host", "%")));
        connection.whenQuery("SELECT account_locked FROM mysql.user LIMIT 1", List.of(row("account_locked", "N")));
        connection.whenQuery("SELECT CURRENT_USER()", List.of(row("CURRENT_USER()", "root@localhost")));
        connection.metadata("MySQL", "8.0.39", 8);

        AccountManagerCapability capability = new MysqlAccountManager().capability(connection.proxy());

        assertEquals(Boolean.FALSE, capability.getPasswordExpirationSupported());
        assertEquals(Boolean.FALSE, capability.getResourceLimitsSupported());
    }

    @Test
    void executeReturnsMysqlErrorDetailsWithRedactedDisplaySql() {
        QueryConnection connection = new QueryConnection();
        AccountOperationRequest command = new AccountOperationRequest();
        command.setActionType(AccountActionTypeEnum.ALTER_PASSWORD.name());
        command.setUser("app");
        command.setHost("%");
        command.setPassword("Secret123!");
        command.setPreviewToken(MysqlAccountSqlBuilder.previewToken(MysqlAccountSqlBuilder.buildSql(command)));
        connection.whenExecuteFails(
                "ALTER USER 'app'@'%' IDENTIFIED BY 'Secret123!'",
                new SQLException("Access denied; you need CREATE USER or SYSTEM_USER", "42000", 1227)
        );

        AccountExecuteResponse result = new MysqlAccountManager().execute(connection.proxy(), command);

        assertFalse(result.getSuccess());
        assertEquals(1227, result.getErrorCode());
        assertEquals("42000", result.getSqlState());
        assertEquals("ALTER USER 'app'@'%' IDENTIFIED BY '******'", result.getSql());
    }

    private static Map<String, Object> row(Object... values) {
        Map<String, Object> row = new HashMap<>();
        for (int i = 0; i < values.length; i += 2) {
            row.put((String) values[i], values[i + 1]);
        }
        return row;
    }

    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler));
    }

    private static final class QueryConnection {
        private final Map<String, List<Map<String, Object>>> results = new HashMap<>();
        private final Map<String, SQLException> executeFailures = new HashMap<>();
        private final List<String> executedSql = new ArrayList<>();
        private DatabaseMetaData metadata = MysqlAccountManagerTest.proxy(DatabaseMetaData.class, (target, method, args) -> switch (method.getName()) {
            case "getDatabaseProductName" -> "MySQL";
            case "getDatabaseProductVersion" -> "8.0";
            case "getDatabaseMajorVersion" -> 8;
            default -> defaultValue(method.getReturnType());
        });

        void whenQuery(String sql, List<Map<String, Object>> rows) {
            results.put(sql, rows);
        }

        void whenExecuteFails(String sql, SQLException exception) {
            executeFailures.put(sql, exception);
        }

        void metadata(String productName, String productVersion, int majorVersion) {
            metadata = MysqlAccountManagerTest.proxy(DatabaseMetaData.class, (target, method, args) -> switch (method.getName()) {
                case "getDatabaseProductName" -> productName;
                case "getDatabaseProductVersion" -> productVersion;
                case "getDatabaseMajorVersion" -> majorVersion;
                default -> defaultValue(method.getReturnType());
            });
        }

        Connection proxy() {
            return MysqlAccountManagerTest.proxy(Connection.class, (target, method, args) -> switch (method.getName()) {
                case "prepareStatement" -> statement((String) args[0]);
                case "getMetaData" -> metadata;
                default -> defaultValue(method.getReturnType());
            });
        }

        List<String> executedSql() {
            return executedSql;
        }

        private PreparedStatement statement(String sql) throws SQLException {
            executedSql.add(sql);
            if (!results.containsKey(sql) && !executeFailures.containsKey(sql)) {
                throw new SQLException("Unexpected SQL: " + sql);
            }
            return MysqlAccountManagerTest.proxy(PreparedStatement.class, (target, method, args) -> switch (method.getName()) {
                case "executeQuery" -> resultSet(results.get(sql));
                case "execute" -> {
                    SQLException exception = executeFailures.get(sql);
                    if (exception != null) {
                        throw exception;
                    }
                    yield true;
                }
                case "close" -> null;
                default -> defaultValue(method.getReturnType());
            });
        }
    }

    private static ResultSet resultSet(List<Map<String, Object>> rows) {
        List<Map<String, Object>> safeRows = new ArrayList<>(rows);
        return proxy(ResultSet.class, new InvocationHandler() {
            private int index = -1;
            private boolean lastValueNull;

            @Override
            public Object invoke(Object target, java.lang.reflect.Method method, Object[] args) throws Throwable {
                return switch (method.getName()) {
                    case "next" -> ++index < safeRows.size();
                    case "getString" -> {
                        Object value = value(args[0]);
                        lastValueNull = value == null;
                        yield value == null ? null : String.valueOf(value);
                    }
                    case "getInt" -> {
                        Object value = value(args[0]);
                        lastValueNull = value == null;
                        yield value instanceof Number number ? number.intValue() : 0;
                    }
                    case "wasNull" -> lastValueNull;
                    case "close" -> null;
                    default -> defaultValue(method.getReturnType());
                };
            }

            private Object value(Object key) throws SQLException {
                if (index < 0 || index >= safeRows.size()) {
                    throw new SQLException("No current row");
                }
                Map<String, Object> row = safeRows.get(index);
                if (key instanceof Integer integer) {
                    return row.values().stream().skip(integer - 1L).findFirst().orElse(null);
                }
                return row.get(String.valueOf(key));
            }
        });
    }

    private static Object defaultValue(Class<?> returnType) {
        if (returnType == Boolean.TYPE) {
            return false;
        }
        if (returnType == Integer.TYPE) {
            return 0;
        }
        if (returnType == Long.TYPE) {
            return 0L;
        }
        if (returnType == Void.TYPE) {
            return null;
        }
        return null;
    }
}

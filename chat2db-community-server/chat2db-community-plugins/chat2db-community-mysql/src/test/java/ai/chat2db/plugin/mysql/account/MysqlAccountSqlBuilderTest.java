package ai.chat2db.plugin.mysql.account;

import ai.chat2db.community.domain.api.enums.plugin.AccountActionTypeEnum;
import ai.chat2db.community.domain.api.enums.plugin.PrivilegeScopeEnum;
import ai.chat2db.community.domain.api.model.account.AccountOperationRequest;
import ai.chat2db.community.tools.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MysqlAccountSqlBuilderTest {

    @Test
    void grantDatabasePrivilegesQuotesIdentifiersAndAccountParts() {
        AccountOperationRequest command = base(AccountActionTypeEnum.GRANT_PRIVILEGE);
        command.setScope(PrivilegeScopeEnum.DATABASE.name());
        command.setDatabaseName("app`prod");
        command.setPrivileges(List.of("SELECT", "SHOW_VIEW"));
        command.setGrantOption(Boolean.TRUE);

        assertEquals(
                "GRANT SELECT, SHOW VIEW ON `app``prod`.* TO 'alice''s'@'10.0.%' WITH GRANT OPTION",
                MysqlAccountSqlBuilder.buildSql(command)
        );
    }

    @Test
    void revokeTablePrivilegesQuotesTableScope() {
        AccountOperationRequest command = base(AccountActionTypeEnum.REVOKE_PRIVILEGE);
        command.setScope(PrivilegeScopeEnum.TABLE.name());
        command.setDatabaseName("app");
        command.setTableName("order`line");
        command.setPrivileges(List.of("UPDATE", "DELETE"));

        assertEquals(
                "REVOKE UPDATE, DELETE ON `app`.`order``line` FROM 'alice''s'@'10.0.%'",
                MysqlAccountSqlBuilder.buildSql(command)
        );
    }

    @Test
    void passwordSqlEscapesQuotesAndBackslashes() {
        AccountOperationRequest command = base(AccountActionTypeEnum.CREATE_USER);
        command.setPassword("p'a\\ss");

        assertEquals(
                "CREATE USER 'alice''s'@'10.0.%' IDENTIFIED BY 'p''a\\\\ss'",
                MysqlAccountSqlBuilder.buildSql(command)
        );
    }

    @Test
    void displaySqlMasksPasswordButTokenUsesExecutableSql() {
        AccountOperationRequest command = base(AccountActionTypeEnum.ALTER_PASSWORD);
        command.setPassword("p'a\\ss");

        String executableSql = MysqlAccountSqlBuilder.buildSql(command);
        String displaySql = MysqlAccountSqlBuilder.buildDisplaySql(command);

        assertEquals("ALTER USER 'alice''s'@'10.0.%' IDENTIFIED BY '******'", displaySql);
        assertNotEquals(displaySql, executableSql);
        assertEquals(
                MysqlAccountSqlBuilder.previewToken(executableSql),
                MysqlAccountSqlBuilder.previewToken(MysqlAccountSqlBuilder.buildSql(command))
        );
    }

    @Test
    void previewTokenChangesWhenSqlChanges() {
        String first = MysqlAccountSqlBuilder.previewToken("GRANT SELECT ON *.* TO 'a'@'%'");
        String second = MysqlAccountSqlBuilder.previewToken("GRANT UPDATE ON *.* TO 'a'@'%'");

        assertNotEquals(first, second);
    }

    @Test
    void showGrantsSqlQuotesAccountParts() {
        assertEquals(
                "SHOW GRANTS FOR 'alice''s'@'10.0.%'",
                MysqlAccountSqlBuilder.showGrantsSql("alice's", "10.0.%")
        );
    }

    @Test
    void renameUserQuotesSourceAndTargetAccounts() {
        AccountOperationRequest command = base(AccountActionTypeEnum.RENAME_USER);
        command.setNewUser("bob's");
        command.setNewHost("localhost");

        assertEquals(
                "RENAME USER 'alice''s'@'10.0.%' TO 'bob''s'@'localhost'",
                MysqlAccountSqlBuilder.buildSql(command)
        );
        assertEquals(
                MysqlAccountSqlBuilder.buildSql(command),
                MysqlAccountSqlBuilder.buildDisplaySql(command)
        );
    }

    @Test
    void renameUserSupportsUsernameHostAndWildcardHostChanges() {
        AccountOperationRequest usernameOnly = base(AccountActionTypeEnum.RENAME_USER);
        usernameOnly.setNewUser("renamed");
        usernameOnly.setNewHost(usernameOnly.getHost());

        AccountOperationRequest hostOnly = base(AccountActionTypeEnum.RENAME_USER);
        hostOnly.setNewUser(hostOnly.getUser());
        hostOnly.setNewHost("localhost");

        AccountOperationRequest combinedWildcard = base(AccountActionTypeEnum.RENAME_USER);
        combinedWildcard.setNewUser("renamed");
        combinedWildcard.setNewHost("172.16.%");

        assertEquals("RENAME USER 'alice''s'@'10.0.%' TO 'renamed'@'10.0.%'",
                MysqlAccountSqlBuilder.buildSql(usernameOnly));
        assertEquals("RENAME USER 'alice''s'@'10.0.%' TO 'alice''s'@'localhost'",
                MysqlAccountSqlBuilder.buildSql(hostOnly));
        assertEquals("RENAME USER 'alice''s'@'10.0.%' TO 'renamed'@'172.16.%'",
                MysqlAccountSqlBuilder.buildSql(combinedWildcard));
    }

    @Test
    void renameUserRequiresTargetUserAndHost() {
        AccountOperationRequest missingUser = base(AccountActionTypeEnum.RENAME_USER);
        missingUser.setNewHost("localhost");
        assertEquals(
                "mysql.account.userRequired",
                assertThrows(BusinessException.class, () -> MysqlAccountSqlBuilder.buildSql(missingUser)).getCode()
        );

        AccountOperationRequest missingHost = base(AccountActionTypeEnum.RENAME_USER);
        missingHost.setNewUser("bob");
        assertEquals(
                "mysql.account.hostRequired",
                assertThrows(BusinessException.class, () -> MysqlAccountSqlBuilder.buildSql(missingHost)).getCode()
        );
    }

    @Test
    void renamePreviewListsVisibleDefinerObjectsForAllObjectTypes() {
        List<String> preparedSql = new ArrayList<>();
        Connection connection = scriptedConnection(preparedSql,
                accountRows(false),
                definerRows("VIEW", "app", "v_orders"),
                definerRows("FUNCTION", "app", "format_order"),
                definerRows("PROCEDURE", "app", "rebuild_orders"),
                definerRows("TRIGGER", "app", "orders_bi"),
                definerRows("EVENT", "app", "rollup_orders")
        );
        AccountOperationRequest command = base(AccountActionTypeEnum.RENAME_USER);
        command.setNewUser("bob");
        command.setNewHost("localhost");

        var preview = new MysqlAccountManager().preview(connection, command);

        assertEquals("RENAME USER 'alice''s'@'10.0.%' TO 'bob'@'localhost'", preview.getSql());
        assertEquals("'alice''s'@'10.0.%'", preview.getOldAccountSql());
        assertEquals("'bob'@'localhost'", preview.getNewAccountSql());
        assertEquals(Boolean.TRUE, preview.getDefinerEnumerationComplete());
        assertEquals(List.of("mysql.account.renameImpactWarning"), preview.getWarningCodes());
        assertEquals(List.of("VIEW", "FUNCTION", "PROCEDURE", "TRIGGER", "EVENT"),
                preview.getDefinerImpacts().stream().map(impact -> impact.getObjectType()).toList());
        assertEquals("alice's@10.0.%", preview.getDefinerImpacts().get(0).getDefiner());
        assertEquals(6, preparedSql.size());
    }

    @Test
    void renamePreviewWarnsWhenDefinerEnumerationIsIncomplete() {
        List<String> preparedSql = new ArrayList<>();
        Connection connection = scriptedConnection(preparedSql,
                accountRows(false),
                definerRows("VIEW", "app", "v_orders"),
                new SQLException("SELECT command denied to user for table ROUTINES"),
                definerRows("PROCEDURE", "app", "rebuild_orders"),
                definerRows("TRIGGER", "app", "orders_bi"),
                definerRows("EVENT", "app", "rollup_orders")
        );
        AccountOperationRequest command = base(AccountActionTypeEnum.RENAME_USER);
        command.setNewUser("bob");
        command.setNewHost("localhost");

        var preview = new MysqlAccountManager().preview(connection, command);

        assertEquals(Boolean.FALSE, preview.getDefinerEnumerationComplete());
        assertEquals(List.of(
                "mysql.account.renameImpactWarning",
                "mysql.account.definerEnumerationIncomplete"
        ), preview.getWarningCodes());
        assertEquals(List.of("VIEW", "PROCEDURE", "TRIGGER", "EVENT"),
                preview.getDefinerImpacts().stream().map(impact -> impact.getObjectType()).toList());
    }

    @Test
    void renamePreviewFailsWhenTargetAccountExists() {
        Connection connection = scriptedConnection(new ArrayList<>(), accountRows(true));
        AccountOperationRequest command = base(AccountActionTypeEnum.RENAME_USER);
        command.setNewUser("bob");
        command.setNewHost("localhost");

        assertEquals(
                "mysql.account.renameTargetExists",
                assertThrows(BusinessException.class, () -> new MysqlAccountManager().preview(connection, command)).getCode()
        );
    }

    @Test
    void renameUserStopsBeforeExecutionWhenTargetAccountExists() {
        List<String> preparedSql = new ArrayList<>();
        Connection connection = (Connection) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[]{Connection.class},
                (proxy, method, args) -> {
                    if ("prepareStatement".equals(method.getName())) {
                        preparedSql.add((String) args[0]);
                        return existingAccountStatement();
                    }
                    return defaultValue(method.getReturnType());
                }
        );
        AccountOperationRequest command = base(AccountActionTypeEnum.RENAME_USER);
        command.setNewUser("bob");
        command.setNewHost("localhost");
        command.setPreviewToken(MysqlAccountSqlBuilder.previewToken(MysqlAccountSqlBuilder.buildSql(command)));

        var result = new MysqlAccountManager().execute(connection, command);

        assertFalse(result.getSuccess());
        assertEquals("mysql.account.renameTargetExists", result.getFailureCode());
        assertEquals(List.of("SELECT 1 FROM mysql.user WHERE User = ? AND Host = ? LIMIT 1"), preparedSql);
    }

    @Test
    void renameUserFailsWhenTargetAccountCannotBeReadBackAfterExecution() {
        List<String> preparedSql = new ArrayList<>();
        Connection connection = (Connection) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[]{Connection.class},
                (proxy, method, args) -> {
                    if ("prepareStatement".equals(method.getName())) {
                        preparedSql.add((String) args[0]);
                        return switch (preparedSql.size()) {
                            case 1, 3, 4 -> accountLookupStatement(false);
                            case 2 -> successfulExecutionStatement();
                            default -> throw new AssertionError("Unexpected statement: " + args[0]);
                        };
                    }
                    return defaultValue(method.getReturnType());
                }
        );
        AccountOperationRequest command = base(AccountActionTypeEnum.RENAME_USER);
        command.setNewUser("bob");
        command.setNewHost("localhost");
        command.setPreviewToken(MysqlAccountSqlBuilder.previewToken(MysqlAccountSqlBuilder.buildSql(command)));

        var result = new MysqlAccountManager().execute(connection, command);

        assertFalse(result.getSuccess());
        assertEquals("mysql.account.renameReadbackFailed", result.getFailureCode());
        assertEquals(List.of(
                "SELECT 1 FROM mysql.user WHERE User = ? AND Host = ? LIMIT 1",
                "RENAME USER 'alice''s'@'10.0.%' TO 'bob'@'localhost'",
                "SELECT 1 FROM mysql.user WHERE User = ? AND Host = ? LIMIT 1",
                "SELECT 1 FROM mysql.user WHERE User = ? AND Host = ? LIMIT 1"
        ), preparedSql);
    }

    private PreparedStatement existingAccountStatement() {
        return accountLookupStatement(true);
    }

    private PreparedStatement accountLookupStatement(boolean hasAccount) {
        ResultSet resultSet = (ResultSet) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[]{ResultSet.class},
                (proxy, method, args) -> "next".equals(method.getName()) ? hasAccount : defaultValue(method.getReturnType())
        );
        return (PreparedStatement) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[]{PreparedStatement.class},
                (proxy, method, args) -> "executeQuery".equals(method.getName()) ? resultSet : defaultValue(method.getReturnType())
        );
    }

    private PreparedStatement successfulExecutionStatement() {
        return (PreparedStatement) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[]{PreparedStatement.class},
                (proxy, method, args) -> defaultValue(method.getReturnType())
        );
    }

    private Connection scriptedConnection(List<String> preparedSql, Object... results) {
        int[] index = {-1};
        return (Connection) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[]{Connection.class},
                (proxy, method, args) -> {
                    if ("prepareStatement".equals(method.getName())) {
                        preparedSql.add((String) args[0]);
                        index[0]++;
                        if (index[0] >= results.length) {
                            throw new AssertionError("Unexpected statement: " + args[0]);
                        }
                        Object result = results[index[0]];
                        if (result instanceof SQLException exception) {
                            return failingQueryStatement(exception);
                        }
                        @SuppressWarnings("unchecked")
                        List<Map<String, String>> rows = (List<Map<String, String>>) result;
                        return queryStatement(rows);
                    }
                    return defaultValue(method.getReturnType());
                }
        );
    }

    private List<Map<String, String>> accountRows(boolean exists) {
        return exists ? List.of(Map.of("1", "1")) : List.of();
    }

    private List<Map<String, String>> definerRows(String objectType, String schemaName, String objectName) {
        return List.of(Map.of(
                "OBJECT_TYPE", objectType,
                "OBJECT_SCHEMA", schemaName,
                "OBJECT_NAME", objectName,
                "DEFINER", "alice's@10.0.%"
        ));
    }

    private PreparedStatement failingQueryStatement(SQLException exception) {
        return (PreparedStatement) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[]{PreparedStatement.class},
                (proxy, method, args) -> {
                    if ("executeQuery".equals(method.getName())) {
                        throw exception;
                    }
                    return defaultValue(method.getReturnType());
                }
        );
    }

    private PreparedStatement queryStatement(List<Map<String, String>> rows) {
        return (PreparedStatement) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[]{PreparedStatement.class},
                (proxy, method, args) -> "executeQuery".equals(method.getName())
                        ? resultSet(rows)
                        : defaultValue(method.getReturnType())
        );
    }

    private ResultSet resultSet(List<Map<String, String>> rows) {
        int[] index = {-1};
        return (ResultSet) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[]{ResultSet.class},
                (proxy, method, args) -> {
                    if ("next".equals(method.getName())) {
                        index[0]++;
                        return index[0] < rows.size();
                    }
                    if ("getString".equals(method.getName())) {
                        return rows.get(index[0]).get(String.valueOf(args[0]));
                    }
                    return defaultValue(method.getReturnType());
                }
        );
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == double.class) {
            return 0D;
        }
        if (type == float.class) {
            return 0F;
        }
        if (type == short.class) {
            return (short) 0;
        }
        if (type == byte.class) {
            return (byte) 0;
        }
        if (type == char.class) {
            return (char) 0;
        }
        return null;
    }

    private AccountOperationRequest base(AccountActionTypeEnum actionType) {
        AccountOperationRequest command = new AccountOperationRequest();
        command.setActionType(actionType.name());
        command.setUser("alice's");
        command.setHost("10.0.%");
        return command;
    }
}

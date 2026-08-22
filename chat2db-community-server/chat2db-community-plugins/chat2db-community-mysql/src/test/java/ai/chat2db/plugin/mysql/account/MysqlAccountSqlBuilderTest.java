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
import java.util.ArrayList;
import java.util.List;

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

    private PreparedStatement existingAccountStatement() {
        ResultSet resultSet = (ResultSet) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[]{ResultSet.class},
                (proxy, method, args) -> "next".equals(method.getName()) ? true : defaultValue(method.getReturnType())
        );
        return (PreparedStatement) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[]{PreparedStatement.class},
                (proxy, method, args) -> "executeQuery".equals(method.getName()) ? resultSet : defaultValue(method.getReturnType())
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

package ai.chat2db.plugin.mysql.account;

import ai.chat2db.community.domain.api.enums.plugin.AccountActionTypeEnum;
import ai.chat2db.community.domain.api.enums.plugin.PrivilegeScopeEnum;
import ai.chat2db.community.domain.api.model.account.AccountOperationRequest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

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
    void grantRoleUsesRoleAccountSyntaxIncludingHost() {
        AccountOperationRequest command = base(AccountActionTypeEnum.GRANT_ROLE);
        command.setRoleName("reporting_role");
        command.setRoleHost("10.%");
        command.setWithAdminOption(Boolean.TRUE);

        assertEquals(
                "GRANT 'reporting_role'@'10.%' TO 'alice''s'@'10.0.%' WITH ADMIN OPTION",
                MysqlAccountSqlBuilder.buildSql(command));
    }

    private AccountOperationRequest base(AccountActionTypeEnum actionType) {
        AccountOperationRequest command = new AccountOperationRequest();
        command.setActionType(actionType.name());
        command.setUser("alice's");
        command.setHost("10.0.%");
        return command;
    }
}

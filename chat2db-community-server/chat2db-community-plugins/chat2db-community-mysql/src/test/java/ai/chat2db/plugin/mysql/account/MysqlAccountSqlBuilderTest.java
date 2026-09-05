package ai.chat2db.plugin.mysql.account;

import ai.chat2db.community.domain.api.enums.plugin.AccountActionTypeEnum;
import ai.chat2db.community.domain.api.enums.plugin.DefaultRoleModeEnum;
import ai.chat2db.community.domain.api.enums.plugin.PrivilegeScopeEnum;
import ai.chat2db.community.domain.api.model.account.AccountInfo;
import ai.chat2db.community.domain.api.model.account.AccountOperationRequest;
import ai.chat2db.community.tools.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
    void grantRoleUsesRoleAccountSyntaxIncludingHost() {
        AccountOperationRequest command = base(AccountActionTypeEnum.GRANT_ROLE);
        command.setRoleName("reporting_role");
        command.setRoleHost("10.%");
        command.setWithAdminOption(Boolean.TRUE);

        assertEquals(
                "GRANT 'reporting_role'@'10.%' TO 'alice''s'@'10.0.%' WITH ADMIN OPTION",
                MysqlAccountSqlBuilder.buildSql(command));
    }

    @Test
    void grantRoleCanTargetAnotherRoleForNestedRoleRelationships() {
        AccountOperationRequest command = base(AccountActionTypeEnum.GRANT_ROLE);
        command.setUser("analyst_role");
        command.setHost("%");
        command.setRoleName("reader_role");
        command.setRoleHost("%");
        command.setWithAdminOption(Boolean.TRUE);

        assertEquals(
                "GRANT 'reader_role'@'%' TO 'analyst_role'@'%' WITH ADMIN OPTION",
                MysqlAccountSqlBuilder.buildSql(command));
    }

    @Test
    void revokeRoleUsesRoleAccountSyntaxIncludingHost() {
        AccountOperationRequest command = base(AccountActionTypeEnum.REVOKE_ROLE);
        command.setRoleName("reporting'role");
        command.setRoleHost("role\\host");

        assertEquals(
                "REVOKE 'reporting''role'@'role\\\\host' FROM 'alice''s'@'10.0.%'",
                MysqlAccountSqlBuilder.buildSql(command));
    }

    @Test
    void setSelectedDefaultRolesKeepsEachRoleHost() {
        AccountOperationRequest command = base(AccountActionTypeEnum.SET_DEFAULT_ROLE);
        command.setDefaultRoleMode(DefaultRoleModeEnum.SELECTED.name());
        command.setRoleList(List.of(role("reader", "%"), role("writer", "10.%")));

        assertEquals(
                "SET DEFAULT ROLE 'reader'@'%', 'writer'@'10.%' TO 'alice''s'@'10.0.%'",
                MysqlAccountSqlBuilder.buildSql(command));
    }

    @Test
    void setAllAndNoDefaultRolesUseMysqlModeKeywords() {
        AccountOperationRequest allRoles = base(AccountActionTypeEnum.SET_DEFAULT_ROLE);
        allRoles.setDefaultRoleMode(DefaultRoleModeEnum.ALL.name());

        AccountOperationRequest noRoles = base(AccountActionTypeEnum.SET_DEFAULT_ROLE);
        noRoles.setDefaultRoleMode(DefaultRoleModeEnum.NONE.name());

        assertEquals("SET DEFAULT ROLE ALL TO 'alice''s'@'10.0.%'", MysqlAccountSqlBuilder.buildSql(allRoles));
        assertEquals("SET DEFAULT ROLE NONE TO 'alice''s'@'10.0.%'", MysqlAccountSqlBuilder.buildSql(noRoles));
    }

    @Test
    void selectedDefaultRoleModeRequiresExplicitRoleList() {
        AccountOperationRequest command = base(AccountActionTypeEnum.SET_DEFAULT_ROLE);
        command.setDefaultRoleMode(DefaultRoleModeEnum.SELECTED.name());

        assertThrows(BusinessException.class, () -> MysqlAccountSqlBuilder.buildSql(command));
    }

    @Test
    void selfRoleGrantCyclePreviewKeepsExactRoleAndTarget() {
        AccountOperationRequest command = base(AccountActionTypeEnum.GRANT_ROLE);
        command.setUser("cycle_role");
        command.setHost("%");
        command.setRoleName("cycle_role");
        command.setRoleHost("%");

        assertEquals(
                "GRANT 'cycle_role'@'%' TO 'cycle_role'@'%'",
                MysqlAccountSqlBuilder.buildDisplaySql(command));
    }

    @Test
    void setRoleIsNotExposedUntilFixedSessionExecutionExists() {
        assertThrows(BusinessException.class, () -> AccountActionTypeEnum.from("SET_ROLE"));
    }

    private AccountOperationRequest base(AccountActionTypeEnum actionType) {
        AccountOperationRequest command = new AccountOperationRequest();
        command.setActionType(actionType.name());
        command.setUser("alice's");
        command.setHost("10.0.%");
        return command;
    }

    private AccountInfo role(String user, String host) {
        AccountInfo role = new AccountInfo();
        role.setUser(user);
        role.setHost(host);
        role.setRole(Boolean.TRUE);
        return role;
    }
}

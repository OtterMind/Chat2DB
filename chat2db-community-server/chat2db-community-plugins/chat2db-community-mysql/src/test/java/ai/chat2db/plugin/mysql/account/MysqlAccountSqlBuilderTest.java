package ai.chat2db.plugin.mysql.account;

import ai.chat2db.community.domain.api.enums.plugin.AccountActionTypeEnum;
import ai.chat2db.community.domain.api.enums.plugin.PrivilegeScopeEnum;
import ai.chat2db.community.domain.api.model.account.AccountOperationRequest;
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
    void displaySqlMasksPassword() {
        AccountOperationRequest command = base(AccountActionTypeEnum.ALTER_PASSWORD);
        command.setPassword("p'a\\ss");

        String executableSql = MysqlAccountSqlBuilder.buildSql(command);
        String displaySql = MysqlAccountSqlBuilder.buildDisplaySql(command);

        assertEquals("ALTER USER 'alice''s'@'10.0.%' IDENTIFIED BY '******'", displaySql);
        assertNotEquals(displaySql, executableSql);
    }

    @Test
    void showGrantsSqlQuotesAccountParts() {
        assertEquals(
                "SHOW GRANTS FOR 'alice''s'@'10.0.%'",
                MysqlAccountSqlBuilder.showGrantsSql("alice's", "10.0.%")
        );
    }

    @Test
    void alterAuthPluginBuildsPluginPasswordAndTlsPreview() {
        AccountOperationRequest command = base(AccountActionTypeEnum.ALTER_AUTH_PLUGIN);
        command.setAuthPlugin("caching_sha2_password");
        command.setPassword("secret");
        command.setTlsRequirement("SPECIFIED");
        command.setTlsCipher("AES256");
        command.setTlsIssuer("issuer");

        assertEquals(
                "ALTER USER 'alice''s'@'10.0.%' IDENTIFIED WITH caching_sha2_password BY 'secret' REQUIRE CIPHER 'AES256' AND ISSUER 'issuer'",
                MysqlAccountSqlBuilder.buildSql(command));
        assertEquals(
                "ALTER USER 'alice''s'@'10.0.%' IDENTIFIED WITH caching_sha2_password BY '******' REQUIRE CIPHER 'AES256' AND ISSUER 'issuer'",
                MysqlAccountSqlBuilder.buildDisplaySql(command));
    }

    @Test
    void alterAuthPluginWithPasswordOnlyUsesIdentifiedByPasswordSyntax() {
        AccountOperationRequest command = base(AccountActionTypeEnum.ALTER_AUTH_PLUGIN);
        command.setPassword("secret");

        assertEquals(
                "ALTER USER 'alice''s'@'10.0.%' IDENTIFIED BY 'secret'",
                MysqlAccountSqlBuilder.buildSql(command));
        assertEquals(
                "ALTER USER 'alice''s'@'10.0.%' IDENTIFIED BY '******'",
                MysqlAccountSqlBuilder.buildDisplaySql(command));
    }

    @Test
    void alterAuthPluginWithPasswordAndTlsUsesIdentifiedByBeforeRequire() {
        AccountOperationRequest command = base(AccountActionTypeEnum.ALTER_AUTH_PLUGIN);
        command.setPassword("secret");
        command.setTlsRequirement("SSL");

        assertEquals(
                "ALTER USER 'alice''s'@'10.0.%' IDENTIFIED BY 'secret' REQUIRE SSL",
                MysqlAccountSqlBuilder.buildSql(command));
    }

    @Test
    void alterAuthPluginCanClearTlsRequirementWithRequireNone() {
        AccountOperationRequest command = base(AccountActionTypeEnum.ALTER_AUTH_PLUGIN);
        command.setTlsRequirement("NONE");

        assertEquals(
                "ALTER USER 'alice''s'@'10.0.%' REQUIRE NONE",
                MysqlAccountSqlBuilder.buildSql(command));
    }

    @Test
    void alterAuthPluginRejectsEmptyAndInvalidTlsCombinations() {
        AccountOperationRequest empty = base(AccountActionTypeEnum.ALTER_AUTH_PLUGIN);
        assertThrows(RuntimeException.class, () -> MysqlAccountSqlBuilder.buildSql(empty));

        AccountOperationRequest x509WithMaterial = base(AccountActionTypeEnum.ALTER_AUTH_PLUGIN);
        x509WithMaterial.setTlsRequirement("X509");
        x509WithMaterial.setTlsSubject("CN=app");
        assertThrows(RuntimeException.class, () -> MysqlAccountSqlBuilder.buildSql(x509WithMaterial));

        AccountOperationRequest specifiedWithoutMaterial = base(AccountActionTypeEnum.ALTER_AUTH_PLUGIN);
        specifiedWithoutMaterial.setTlsRequirement("SPECIFIED");
        assertThrows(RuntimeException.class, () -> MysqlAccountSqlBuilder.buildSql(specifiedWithoutMaterial));
    }

    @Test
    void alterAuthPluginRejectsUnsafePluginName() {
        AccountOperationRequest command = base(AccountActionTypeEnum.ALTER_AUTH_PLUGIN);
        command.setAuthPlugin("native_password; DROP USER root");
        assertThrows(RuntimeException.class, () -> MysqlAccountSqlBuilder.buildSql(command));
    }

    @Test
    void alterAuthPluginRequiresPasswordWhenPluginIsIncluded() {
        AccountOperationRequest command = base(AccountActionTypeEnum.ALTER_AUTH_PLUGIN);
        command.setAuthPlugin("caching_sha2_password");
        command.setTlsRequirement("SSL");

        assertThrows(RuntimeException.class, () -> MysqlAccountSqlBuilder.buildSql(command));
    }

    private AccountOperationRequest base(AccountActionTypeEnum actionType) {
        AccountOperationRequest command = new AccountOperationRequest();
        command.setActionType(actionType.name());
        command.setUser("alice's");
        command.setHost("10.0.%");
        return command;
    }
}

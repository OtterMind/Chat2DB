package ai.chat2db.plugin.mysql.account;

import ai.chat2db.community.domain.api.enums.plugin.AccountActionTypeEnum;
import ai.chat2db.community.domain.api.enums.plugin.PrivilegeScopeEnum;
import ai.chat2db.community.domain.api.model.account.AccountOperationRequest;
import org.junit.jupiter.api.Test;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class MysqlColumnPrivilegeIntegrationTest {

    @Test
    void columnGrantAndRevokeRestrictRealMysqlAccount() throws Exception {
        String url = System.getenv("CHAT2DB_SEC005_URL");
        assumeTrue(url != null && !url.isBlank(), "Local MYSQL-SEC-005 fixture not configured");
        String adminPassword = System.getenv("CHAT2DB_SEC005_ADMIN_PASSWORD");
        String userPassword = System.getenv("CHAT2DB_SEC005_USER_PASSWORD");
        assumeTrue(adminPassword != null && userPassword != null, "Local MYSQL-SEC-005 passwords not configured");
        Path connectorJar = Path.of(System.getProperty("user.home"), ".m2", "repository",
                "com", "mysql", "mysql-connector-j", "8.0.33", "mysql-connector-j-8.0.33.jar");
        assumeTrue(Files.isRegularFile(connectorJar), "Local Connector/J jar not available");

        try (URLClassLoader loader = new URLClassLoader(new URL[]{connectorJar.toUri().toURL()},
                ClassLoader.getPlatformClassLoader())) {
            Driver driver = (Driver) Class.forName("com.mysql.cj.jdbc.Driver", true, loader)
                    .getDeclaredConstructor().newInstance();
            MysqlAccountManager manager = new MysqlAccountManager();
            try (Connection admin = connect(driver, url, "sec005_admin", adminPassword)) {
                AccountOperationRequest grant = command(AccountActionTypeEnum.GRANT_PRIVILEGE);
                grant.setPreviewToken(manager.preview(grant).getPreviewToken());
                try {
                    assertTrue(manager.execute(admin, grant).getSuccess());
                    assertTrue(manager.showGrants(admin, "sec005_user", "%").stream()
                            .anyMatch(sql -> sql.contains("SELECT (`name`)")));
                    try (Connection limited = connect(driver, url, "sec005_user", userPassword)) {
                        try (Statement statement = limited.createStatement();
                             ResultSet rows = statement.executeQuery("SELECT `name` FROM `sec005_employees`")) {
                            assertTrue(rows.next());
                        }
                        try (Statement statement = limited.createStatement()) {
                            assertThrows(SQLException.class,
                                    () -> statement.executeQuery("SELECT `salary` FROM `sec005_employees`"));
                        }
                    }
                } finally {
                    AccountOperationRequest revoke = command(AccountActionTypeEnum.REVOKE_PRIVILEGE);
                    revoke.setPreviewToken(manager.preview(revoke).getPreviewToken());
                    assertTrue(manager.execute(admin, revoke).getSuccess());
                }
                assertFalse(manager.showGrants(admin, "sec005_user", "%").stream()
                        .anyMatch(sql -> sql.contains("SELECT (`name`)")));
            }
        }
    }

    private static AccountOperationRequest command(AccountActionTypeEnum action) {
        AccountOperationRequest request = new AccountOperationRequest();
        request.setActionType(action.name());
        request.setUser("sec005_user");
        request.setHost("%");
        request.setScope(PrivilegeScopeEnum.COLUMN.name());
        request.setDatabaseName("sec005_test");
        request.setTableName("sec005_employees");
        request.setPrivileges(List.of("SELECT"));
        request.setColumnList(List.of("name"));
        return request;
    }

    private static Connection connect(Driver driver, String url, String user, String password) throws SQLException {
        Properties properties = new Properties();
        properties.setProperty("user", user);
        properties.setProperty("password", password);
        Connection connection = driver.connect(url, properties);
        assertTrue(connection != null, "Connector/J did not accept the fixture URL");
        return connection;
    }
}

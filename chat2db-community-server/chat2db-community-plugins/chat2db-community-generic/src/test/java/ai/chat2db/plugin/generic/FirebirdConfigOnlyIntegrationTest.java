package ai.chat2db.plugin.generic;

import ai.chat2db.community.domain.api.config.DBConfig;
import ai.chat2db.community.domain.api.config.DriverConfig;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Properties;

import ai.chat2db.community.domain.api.model.metadata.Table;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * End-to-end proof that the configuration-only FIREBIRD entry in generic.json
 * is sufficient to drive a real database: the declared driver class loads, the
 * declared URL template connects, SQL executes, and the JDBC metadata that
 * DefaultMetaService relies on lists the created table.
 *
 * Requires the local test container and skips itself otherwise:
 * docker run -d --name c2d-test-firebird -e FIREBIRD_ROOT_PASSWORD=masterkey \
 *   -e FIREBIRD_DATABASE=demo.fdb -p 127.0.0.1:3050:3050 firebirdsql/firebird:5
 */
class FirebirdConfigOnlyIntegrationTest {

    private static final String HOST = "127.0.0.1";
    private static final int PORT = 3050;

    private static boolean containerReachable() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(HOST, PORT), 1500);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private DriverConfig firebirdDriver() {
        return new GenericPlugin().getDBConfigList().stream()
                .filter(config -> "FIREBIRD".equals(config.getDbType()))
                .findFirst()
                .map(DBConfig::getDefaultDriverConfig)
                .orElseThrow();
    }

    @Test
    void configuredDriverConnectsAndServesJdbcMetadata() throws Exception {
        assumeTrue(containerReachable(), "Firebird test container not running; skipping");

        DriverConfig driver = firebirdDriver();
        Class.forName(driver.getJdbcDriverClass());

        Properties properties = new Properties();
        properties.setProperty("user", "SYSDBA");
        properties.setProperty("password", "masterkey");
        properties.setProperty("charSet", "utf-8");

        try (Connection connection = DriverManager.getConnection(driver.getUrl(), properties)) {
            try (Statement statement = connection.createStatement()) {
                dropTableIfExists(statement);
                statement.execute("CREATE TABLE C2D_IT_SMOKE (ID INTEGER NOT NULL PRIMARY KEY, NAME VARCHAR(50))");
            }
            // Firebird DDL becomes visible to DML after commit.
            if (!connection.getAutoCommit()) {
                connection.commit();
            }
            try (Statement statement = connection.createStatement()) {
                assertEquals(1, statement.executeUpdate(
                        "INSERT INTO C2D_IT_SMOKE (ID, NAME) VALUES (1, 'chat2db')"));
                try (ResultSet resultSet = statement.executeQuery(
                        "SELECT NAME FROM C2D_IT_SMOKE WHERE ID = 1")) {
                    assertTrue(resultSet.next());
                    assertEquals("chat2db", resultSet.getString(1));
                }
            }

            DatabaseMetaData metaData = connection.getMetaData();
            boolean found = false;
            try (ResultSet tables = metaData.getTables(null, null, "C2D_IT_SMOKE", null)) {
                while (tables.next()) {
                    found = "C2D_IT_SMOKE".equals(tables.getString("TABLE_NAME"));
                    if (found) {
                        break;
                    }
                }
            }
            assertTrue(found, "JDBC DatabaseMetaData should list the created table");

            try (Statement statement = connection.createStatement()) {
                statement.execute("DROP TABLE C2D_IT_SMOKE");
            }
        }
    }

    /**
     * Regression for the empty database node in the connection tree: Firebird
     * has no catalog or schema concept (getCatalogs()/getSchemas() return
     * nothing), so generic.json must declare supportDatabase=false and
     * supportSchema=false, and table listing must work without either context.
     */
    @Test
    void firebirdDeclaresNeitherDatabasesNorSchemasAndListsTablesDirectly() throws Exception {
        DBConfig config = new GenericPlugin().getDBConfigList().stream()
                .filter(item -> "FIREBIRD".equals(item.getDbType()))
                .findFirst().orElseThrow();
        assertFalse(config.isSupportDatabase(),
                "Firebird has no catalogs: the tree must not show a database level");
        assertFalse(config.isSupportSchema(),
                "Firebird has no schemas: the tree must not show a schema level");

        assumeTrue(containerReachable(), "Firebird test container not running; skipping");
        DriverConfig driver = firebirdDriver();
        Class.forName(driver.getJdbcDriverClass());
        Properties properties = new Properties();
        properties.setProperty("user", "SYSDBA");
        properties.setProperty("password", "masterkey");
        properties.setProperty("charSet", "utf-8");
        try (Connection connection = DriverManager.getConnection(driver.getUrl(), properties)) {
            try (ResultSet catalogs = connection.getMetaData().getCatalogs()) {
                assertTrue(!catalogs.next(), "Jaybird reports no catalogs");
            }
            try (Statement statement = connection.createStatement()) {
                dropTableIfExists(statement);
                statement.execute("CREATE TABLE C2D_IT_SMOKE (ID INTEGER NOT NULL PRIMARY KEY)");
            }
            if (!connection.getAutoCommit()) {
                connection.commit();
            }
            List<Table> tables = new GenericMetaData(config).tables(connection, null, null, null);
            assertTrue(tables.stream().anyMatch(item -> "C2D_IT_SMOKE".equals(item.getName())),
                    "table listing must work without database or schema context");
            assertTrue(tables.stream().noneMatch(item -> item.getName() != null
                            && (item.getName().startsWith("MON$") || item.getName().startsWith("RDB$")
                                || item.getName().startsWith("SEC$"))),
                    "engine-internal MON$/RDB$/SEC$ relations must not pollute the table listing");
            try (Statement statement = connection.createStatement()) {
                statement.execute("DROP TABLE C2D_IT_SMOKE");
            }
        }
    }

    private void dropTableIfExists(Statement statement) {
        try {
            statement.execute("DROP TABLE C2D_IT_SMOKE");
        } catch (SQLException ignored) {
            // table absent on a fresh container
        }
    }
}

package ai.chat2db.plugin.generic;

import ai.chat2db.community.domain.api.config.DBConfig;
import ai.chat2db.community.domain.api.config.DriverConfig;
import ai.chat2db.community.domain.api.model.metadata.Database;
import ai.chat2db.community.domain.api.model.metadata.Schema;
import ai.chat2db.community.domain.api.model.metadata.Table;
import ai.chat2db.community.domain.api.model.metadata.TableColumn;
import ai.chat2db.spi.DefaultSQLExecutor;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Business-flow coverage for configuration-only databases through the same
 * code the application uses: connection establishment and validity (what
 * pre_connect exercises), database listing, schema listing, table listing,
 * column listing, and SQL execution via GenericMetaData/DefaultSQLExecutor.
 *
 * HSQLDB and Derby run in-memory everywhere; the Firebird flow runs when the
 * local test container is reachable and skips cleanly otherwise.
 */
class GenericBusinessFlowIntegrationTest {

    private final GenericMetaData metaData = new GenericMetaData();

    private String declaredDriverClass(String dbType) {
        return new GenericPlugin().getDBConfigList().stream()
                .filter(config -> dbType.equals(config.getDbType()))
                .findFirst()
                .map(DBConfig::getDefaultDriverConfig)
                .map(DriverConfig::getJdbcDriverClass)
                .orElseThrow();
    }

    private Connection connect(String dbType, String url, Properties properties) throws Exception {
        Class.forName(declaredDriverClass(dbType));
        Connection connection = properties == null
                ? DriverManager.getConnection(url)
                : DriverManager.getConnection(url, properties);
        // Connection test: the essence of the pre_connect business flow.
        assertNotNull(connection);
        assertTrue(connection.isValid(5), dbType + " connection should validate");
        return connection;
    }

    private void runBusinessFlow(String dbType, Connection connection, String schemaFilter,
                                 String tableName, boolean expectDatabases) throws Exception {
        try (connection) {
            // Database listing.
            List<Database> databases = metaData.databases(connection);
            assertNotNull(databases, dbType + " database listing should not be null");
            if (expectDatabases) {
                assertTrue(databases.size() > 0, dbType + " should report at least one database");
            }

            // Schema listing.
            List<Schema> schemas = metaData.schemas(connection, null);
            assertNotNull(schemas, dbType + " schema listing should not be null");
            if (schemaFilter != null) {
                assertTrue(schemas.stream().anyMatch(schema -> schemaFilter.equals(schema.getName())),
                        dbType + " schemas should contain " + schemaFilter + " but were " + schemas.size());
            }

            // SQL execution: DDL + DML + query through the app's executor.
            DefaultSQLExecutor executor = DefaultSQLExecutor.getInstance();
            try {
                executor.execute(connection, "DROP TABLE " + tableName, resultSet -> null);
                if (!connection.getAutoCommit()) {
                    connection.commit();
                }
            } catch (RuntimeException ignored) {
                // table absent on a fresh engine
            }
            executor.execute(connection,
                    "CREATE TABLE " + tableName + " (ID INTEGER NOT NULL PRIMARY KEY, NAME VARCHAR(50))",
                    resultSet -> null);
            if (!connection.getAutoCommit()) {
                connection.commit();
            }
            // executeUpdate reports success only; the row count is verified by the query below.
            assertTrue(executor.executeUpdate(
                            "INSERT INTO " + tableName + " (ID, NAME) VALUES (1, 'chat2db')", connection, 1)
                    .getSuccess());
            Integer count = executor.execute(connection,
                    "SELECT COUNT(*) FROM " + tableName,
                    resultSet -> {
                        assertTrue(resultSet.next());
                        return resultSet.getInt(1);
                    });
            assertEquals(1, count, dbType + " query should see the inserted row");

            // Table listing through the app's metadata path.
            List<Table> tables = metaData.tables(connection, null, schemaFilter, tableName);
            assertTrue(tables.stream().anyMatch(table -> tableName.equals(table.getName())),
                    dbType + " table listing should contain " + tableName);

            // Column listing.
            List<TableColumn> columns = metaData.columns(connection, null, schemaFilter, tableName);
            assertEquals(2, columns.size(), dbType + " should list both columns");
            assertTrue(columns.stream().anyMatch(column -> "ID".equalsIgnoreCase(column.getName())));
            assertTrue(columns.stream().anyMatch(column -> "NAME".equalsIgnoreCase(column.getName())));

            executor.execute(connection, "DROP TABLE " + tableName, resultSet -> null);
        }
    }

    @Test
    void hsqldbFullBusinessFlow() throws Exception {
        Connection connection = connect("HSQLDB", "jdbc:hsqldb:mem:c2d_flow;shutdown=true", null);
        runBusinessFlow("HSQLDB", connection, "PUBLIC", "C2D_FLOW_HSQLDB", true);
    }

    @Test
    void derbyFullBusinessFlow() throws Exception {
        Connection connection = connect("DERBY", "jdbc:derby:memory:c2d_flow;create=true", null);
        runBusinessFlow("DERBY", connection, "APP", "C2D_FLOW_DERBY", false);
    }

    @Test
    void firebirdFullBusinessFlow() throws Exception {
        assumeTrue(reachable("127.0.0.1", 3050), "Firebird test container not running; skipping");
        Properties properties = new Properties();
        properties.setProperty("user", "SYSDBA");
        properties.setProperty("password", "masterkey");
        properties.setProperty("charSet", "utf-8");
        DriverConfig driver = new GenericPlugin().getDBConfigList().stream()
                .filter(config -> "FIREBIRD".equals(config.getDbType())).findFirst()
                .map(DBConfig::getDefaultDriverConfig).orElseThrow();
        Connection connection = connect("FIREBIRD", driver.getUrl(), properties);
        // Firebird has no schemas; skip the schema containment filter.
        runBusinessFlow("FIREBIRD", connection, null, "C2D_FLOW_FB", false);
    }

    private static boolean reachable(String host, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 1500);
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}

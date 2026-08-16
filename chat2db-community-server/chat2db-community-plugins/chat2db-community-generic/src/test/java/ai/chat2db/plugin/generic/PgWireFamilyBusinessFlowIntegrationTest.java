package ai.chat2db.plugin.generic;

import ai.chat2db.community.domain.api.model.metadata.Schema;
import ai.chat2db.community.domain.api.model.metadata.Table;
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
 * Business flows for PostgreSQL-wire databases registered through
 * configuration only: QuestDB and CrateDB run against their official Docker
 * images using the PostgreSQL driver that generic.json declares, through the
 * same GenericMetaData/DefaultSQLExecutor paths the application uses. Each
 * flow asserts the engine's real dialect quirks (QuestDB: no primary keys,
 * STRING type; CrateDB: REFRESH TABLE before reads, doc schema). Tests skip
 * cleanly when the corresponding container is not running.
 *
 * docker run -d --name c2d-test-questdb -p 127.0.0.1:8812:8812 questdb/questdb:8.2.1
 * docker run -d --name c2d-test-cratedb -p 127.0.0.1:5433:5432 crate:5.9 -Cdiscovery.type=single-node
 */
class PgWireFamilyBusinessFlowIntegrationTest {

    private final GenericMetaData metaData = new GenericMetaData();
    private final DefaultSQLExecutor executor = DefaultSQLExecutor.getInstance();

    private Connection connect(String url, String user, String password) throws Exception {
        Class.forName("org.postgresql.Driver");
        Properties properties = new Properties();
        properties.setProperty("user", user);
        if (password != null) {
            properties.setProperty("password", password);
        }
        properties.setProperty("sslmode", "disable");
        Connection connection = DriverManager.getConnection(url, properties);
        assertNotNull(connection);
        assertTrue(connection.isValid(5), url + " connection should validate");
        return connection;
    }

    private void dropQuietly(Connection connection, String tableName) {
        try {
            executor.execute(connection, "DROP TABLE " + tableName, resultSet -> null);
        } catch (RuntimeException ignored) {
            // table absent on a fresh engine
        }
    }

    @Test
    void questdbFullBusinessFlow() throws Exception {
        assumeTrue(reachable(8812), "QuestDB test container not running; skipping");
        try (Connection connection = connect("jdbc:postgresql://127.0.0.1:8812/qdb", "admin", "quest")) {
            String table = "c2d_flow_qdb";
            dropQuietly(connection, table);

            // QuestDB has no primary keys and uses STRING columns.
            executor.execute(connection,
                    "CREATE TABLE " + table + " (id INT, name STRING)", resultSet -> null);
            assertTrue(executor.executeUpdate(
                    "INSERT INTO " + table + " (id, name) VALUES (1, 'chat2db')", connection, 1).getSuccess());
            Integer count = executor.execute(connection,
                    "SELECT COUNT(*) FROM " + table, resultSet -> {
                        assertTrue(resultSet.next());
                        return resultSet.getInt(1);
                    });
            assertEquals(1, count, "QuestDB query should see the inserted row");

            // Table listing through the app's metadata path (pg_catalog emulation).
            List<Table> tables = metaData.tables(connection, null, null, table);
            assertTrue(tables.stream().anyMatch(item -> table.equals(item.getName())),
                    "QuestDB table listing should contain " + table);

            dropQuietly(connection, table);
        }
    }

    @Test
    void cratedbFullBusinessFlow() throws Exception {
        assumeTrue(reachable(5433), "CrateDB test container not running; skipping");
        try (Connection connection = connect("jdbc:postgresql://127.0.0.1:5433/doc", "crate", null)) {
            String table = "c2d_flow_crate";
            dropQuietly(connection, table);

            List<Schema> schemas = metaData.schemas(connection, null);
            assertNotNull(schemas, "CrateDB schema listing should not be null");

            executor.execute(connection,
                    "CREATE TABLE " + table + " (id INTEGER PRIMARY KEY, name TEXT)", resultSet -> null);
            assertTrue(executor.executeUpdate(
                    "INSERT INTO " + table + " (id, name) VALUES (1, 'chat2db')", connection, 1).getSuccess());
            // CrateDB makes writes visible after an explicit refresh.
            executor.execute(connection, "REFRESH TABLE " + table, resultSet -> null);
            Integer count = executor.execute(connection,
                    "SELECT COUNT(*) FROM " + table, resultSet -> {
                        assertTrue(resultSet.next());
                        return resultSet.getInt(1);
                    });
            assertEquals(1, count, "CrateDB query should see the inserted row after refresh");

            List<Table> tables = metaData.tables(connection, null, "doc", table);
            assertTrue(tables.stream().anyMatch(item -> table.equals(item.getName())),
                    "CrateDB table listing should contain " + table);

            dropQuietly(connection, table);
        }
    }

    @Test
    void yugabytedbFullBusinessFlow() throws Exception {
        assumeTrue(reachable(5435), "YugabyteDB test container not running; skipping");
        try (Connection connection = connect("jdbc:postgresql://127.0.0.1:5435/yugabyte", "yugabyte", "yugabyte")) {
            String table = "c2d_flow_yb";
            dropQuietly(connection, table);

            executor.execute(connection,
                    "CREATE TABLE " + table + " (id INTEGER PRIMARY KEY, name TEXT)", resultSet -> null);
            assertTrue(executor.executeUpdate(
                    "INSERT INTO " + table + " (id, name) VALUES (1, 'chat2db')", connection, 1).getSuccess());
            Integer count = executor.execute(connection,
                    "SELECT COUNT(*) FROM " + table, resultSet -> {
                        assertTrue(resultSet.next());
                        return resultSet.getInt(1);
                    });
            assertEquals(1, count, "YugabyteDB query should see the inserted row");

            List<Table> tables = metaData.tables(connection, null, "public", table);
            assertTrue(tables.stream().anyMatch(item -> table.equals(item.getName())),
                    "YugabyteDB table listing should contain " + table);

            dropQuietly(connection, table);
        }
    }

    @Test
    void greenplumFullBusinessFlow() throws Exception {
        assumeTrue(reachable(5436), "Greenplum test container not running; skipping");
        try (Connection connection = connect("jdbc:postgresql://127.0.0.1:5436/postgres", "gpadmin", "gpadmin")) {
            String table = "c2d_flow_gp";
            dropQuietly(connection, table);

            // Greenplum distributes by the first column when no clause is given.
            executor.execute(connection,
                    "CREATE TABLE " + table + " (id INTEGER, name TEXT) DISTRIBUTED BY (id)", resultSet -> null);
            assertTrue(executor.executeUpdate(
                    "INSERT INTO " + table + " (id, name) VALUES (1, 'chat2db')", connection, 1).getSuccess());
            Integer count = executor.execute(connection,
                    "SELECT COUNT(*) FROM " + table, resultSet -> {
                        assertTrue(resultSet.next());
                        return resultSet.getInt(1);
                    });
            assertEquals(1, count, "Greenplum query should see the inserted row");

            List<Table> tables = metaData.tables(connection, null, "public", table);
            assertTrue(tables.stream().anyMatch(item -> table.equals(item.getName())),
                    "Greenplum table listing should contain " + table);

            dropQuietly(connection, table);
        }
    }

    /**
     * Regression for the table-browse failures recorded in the operation log:
     * "select * from qdb.telemetry" -> table does not exist. QuestDB's namespace
     * is flat, so the browse SQL must reference the bare table name.
     */
    @Test
    void questdbTableBrowseUsesUnqualifiedName() throws Exception {
        assumeTrue(reachable(8812), "QuestDB test container not running; skipping");
        try (Connection connection = connect("jdbc:postgresql://127.0.0.1:8812/qdb", "admin", "quest")) {
            String table = "c2d_browse_qdb";
            dropQuietly(connection, table);
            executor.execute(connection, "CREATE TABLE " + table + " (id INT)", resultSet -> null);
            assertTrue(executor.executeUpdate(
                    "INSERT INTO " + table + " (id) VALUES (7)", connection, 1).getSuccess());

            String qualified = new GenericMetaData(configFor("QUESTDB"))
                    .getQualifiedTableName("qdb", null, table);
            assertEquals(table, qualified, "QuestDB browse SQL must not carry the database prefix");
            Integer rows = executor.execute(connection, "select * from " + qualified, resultSet -> {
                int n = 0;
                while (resultSet.next()) {
                    n++;
                }
                return n;
            });
            assertEquals(1, rows, "browse SQL built from getQualifiedTableName should read the table");

            dropQuietly(connection, table);
        }
    }

    /**
     * Regression for "select * from doc.crate.c2d_flow_crate" -> Unexpected
     * catalog name: doc. The JDBC catalog CrateDB reports must never reach the
     * SQL; a schema-qualified reference is the correct browse form, including
     * for a schema that happens to be named "crate".
     */
    @Test
    void cratedbTableBrowseDropsCatalogQualifier() throws Exception {
        assumeTrue(reachable(5433), "CrateDB test container not running; skipping");
        try (Connection connection = connect("jdbc:postgresql://127.0.0.1:5433/doc", "crate", null)) {
            String table = "c2d_browse_crate";
            dropQuietly(connection, "crate." + table);
            executor.execute(connection,
                    "CREATE TABLE crate." + table + " (id INTEGER PRIMARY KEY)", resultSet -> null);
            assertTrue(executor.executeUpdate(
                    "INSERT INTO crate." + table + " (id) VALUES (1)", connection, 1).getSuccess());
            executor.execute(connection, "REFRESH TABLE crate." + table, resultSet -> null);

            String qualified = new GenericMetaData(configFor("CRATEDB"))
                    .getQualifiedTableName("doc", "crate", table);
            assertEquals("crate." + table, qualified,
                    "CrateDB browse SQL must qualify with the schema only");
            Integer rows = executor.execute(connection, "select * from " + qualified, resultSet -> {
                int n = 0;
                while (resultSet.next()) {
                    n++;
                }
                return n;
            });
            assertEquals(1, rows, "browse SQL built from getQualifiedTableName should read the table");

            dropQuietly(connection, "crate." + table);
        }
    }

    private ai.chat2db.community.domain.api.config.DBConfig configFor(String dbType) {
        return new GenericPlugin().getDBConfigList().stream()
                .filter(config -> dbType.equals(config.getDbType()))
                .findFirst().orElseThrow();
    }

    private static boolean reachable(int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 1500);
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}

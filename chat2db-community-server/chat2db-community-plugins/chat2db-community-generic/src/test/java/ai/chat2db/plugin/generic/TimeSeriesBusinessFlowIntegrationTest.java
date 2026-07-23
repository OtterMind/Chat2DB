package ai.chat2db.plugin.generic;

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
 * Business flows for the time-series databases registered through
 * configuration only, against their official Docker images.
 *
 * TimescaleDB is PostgreSQL plus the timescaledb extension, so the flow
 * proves the actual time-series capability by creating a hypertable.
 * IoTDB speaks its own tree-model SQL over JDBC; the flow covers connect,
 * write, and query through the app's executor -- JDBC DatabaseMetaData is
 * not part of IoTDB's contract, which is exactly what a thin follow-up
 * plugin would need to add.
 *
 * docker run -d --name c2d-test-timescaledb -e POSTGRES_PASSWORD=postgres \
 *   -p 127.0.0.1:5434:5432 timescale/timescaledb:2.17.2-pg16
 * docker run -d --name c2d-test-iotdb -p 127.0.0.1:6667:6667 apache/iotdb:1.3.2-standalone
 */
class TimeSeriesBusinessFlowIntegrationTest {

    private final GenericMetaData metaData = new GenericMetaData();
    private final DefaultSQLExecutor executor = DefaultSQLExecutor.getInstance();

    @Test
    void timescaledbHypertableBusinessFlow() throws Exception {
        assumeTrue(reachable(5434), "TimescaleDB test container not running; skipping");
        Class.forName("org.postgresql.Driver");
        Properties properties = new Properties();
        properties.setProperty("user", "postgres");
        properties.setProperty("password", "postgres");
        try (Connection connection = DriverManager.getConnection(
                "jdbc:postgresql://127.0.0.1:5434/postgres", properties)) {
            assertTrue(connection.isValid(5));
            String table = "c2d_flow_ts";
            try {
                executor.execute(connection, "DROP TABLE " + table, resultSet -> null);
            } catch (RuntimeException ignored) {
                // absent on fresh container
            }

            executor.execute(connection,
                    "CREATE TABLE " + table + " (time TIMESTAMPTZ NOT NULL, temperature DOUBLE PRECISION)",
                    resultSet -> null);
            // The actual time-series capability: promote to a hypertable.
            executor.execute(connection,
                    "SELECT create_hypertable('" + table + "', 'time')", resultSet -> null);
            assertTrue(executor.executeUpdate(
                    "INSERT INTO " + table + " (time, temperature) VALUES (now(), 36.5)",
                    connection, 1).getSuccess());
            Integer count = executor.execute(connection,
                    "SELECT COUNT(*) FROM " + table, resultSet -> {
                        assertTrue(resultSet.next());
                        return resultSet.getInt(1);
                    });
            assertEquals(1, count, "TimescaleDB query should see the inserted point");

            List<Table> tables = metaData.tables(connection, null, "public", table);
            assertTrue(tables.stream().anyMatch(item -> table.equals(item.getName())),
                    "TimescaleDB table listing should contain the hypertable");

            executor.execute(connection, "DROP TABLE " + table, resultSet -> null);
        }
    }

    @Test
    void iotdbWriteAndQueryBusinessFlow() throws Exception {
        assumeTrue(reachable(6667), "IoTDB test container not running; skipping");
        Class.forName("org.apache.iotdb.jdbc.IoTDBDriver");
        Properties properties = new Properties();
        properties.setProperty("user", "root");
        properties.setProperty("password", "root");
        try (Connection connection = DriverManager.getConnection(
                "jdbc:iotdb://127.0.0.1:6667/", properties)) {
            assertNotNull(connection);

            try {
                executor.execute(connection, "DELETE DATABASE root.c2d", resultSet -> null);
            } catch (RuntimeException ignored) {
                // absent on fresh container
            }
            executor.execute(connection, "CREATE DATABASE root.c2d", resultSet -> null);
            executor.execute(connection,
                    "INSERT INTO root.c2d.device1(timestamp, temperature) VALUES (now(), 36.5)",
                    resultSet -> null);
            Integer rows = executor.execute(connection,
                    "SELECT temperature FROM root.c2d.device1", resultSet -> {
                        int seen = 0;
                        while (resultSet.next()) {
                            seen++;
                        }
                        return seen;
                    });
            assertEquals(1, rows, "IoTDB query should see the inserted point");

            executor.execute(connection, "DELETE DATABASE root.c2d", resultSet -> null);
        }
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

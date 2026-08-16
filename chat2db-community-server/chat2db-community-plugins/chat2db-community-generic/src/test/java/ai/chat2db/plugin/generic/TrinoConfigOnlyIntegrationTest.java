package ai.chat2db.plugin.generic;

import ai.chat2db.community.domain.api.config.DBConfig;
import ai.chat2db.community.domain.api.config.DriverConfig;
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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * End-to-end proof that the configuration-only TRINO entry in generic.json
 * drives a real coordinator: the declared driver class loads, SQL executes
 * against the built-in tpch catalog, and the JDBC metadata path that
 * DefaultMetaService relies on lists tpch tables. Runs against the container
 * from start-test-databases.sh and skips cleanly when it is absent:
 *
 * docker run -d --name c2d-test-trino -p 127.0.0.1:8085:8080 trinodb/trino:475
 */
class TrinoConfigOnlyIntegrationTest {

    private static final int PORT = 8085;

    private final DefaultSQLExecutor executor = DefaultSQLExecutor.getInstance();

    private static boolean containerReachable() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", PORT), 1500);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private DBConfig trinoConfig() {
        return new GenericPlugin().getDBConfigList().stream()
                .filter(config -> "TRINO".equals(config.getDbType()))
                .findFirst().orElseThrow();
    }

    @Test
    void configuredDriverQueriesTpchAndServesJdbcMetadata() throws Exception {
        assumeTrue(containerReachable(), "Trino test container not running; skipping");

        DBConfig config = trinoConfig();
        DriverConfig driver = config.getDefaultDriverConfig();
        Class.forName(driver.getJdbcDriverClass());

        Properties properties = new Properties();
        properties.setProperty("user", "chat2db");
        try (Connection connection = DriverManager.getConnection(
                "jdbc:trino://127.0.0.1:" + PORT, properties)) {
            assertTrue(connection.isValid(10), "Trino connection should validate");

            Integer nations = executor.execute(connection,
                    "SELECT count(*) FROM tpch.tiny.nation", resultSet -> {
                        assertTrue(resultSet.next());
                        return resultSet.getInt(1);
                    });
            assertEquals(25, nations, "tpch.tiny.nation always has 25 rows");

            List<Table> tables = new GenericMetaData(config).tables(connection, "tpch", "tiny", "nation");
            assertTrue(tables.stream().anyMatch(item -> "nation".equals(item.getName())),
                    "Trino metadata listing should contain tpch.tiny.nation");
        }
    }
}

package ai.chat2db.plugin.h2;

import ai.chat2db.community.domain.api.config.DriverConfig;
import ai.chat2db.spi.model.datasource.ConnectInfo;
import ai.chat2db.spi.sql.Chat2DBContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * Regression test for {@link H2DBManager#connectDatabase}.
 * Verifies that a SQLException during SET SCHEMA is caught and logged
 * instead of propagating to the caller.
 */
class H2DBManagerTest {

    private H2DBManager manager = new H2DBManager();

    @AfterEach
    void cleanup() {
        Chat2DBContext.removeContext();
    }

    private void putH2Context(String schemaName) {
        ConnectInfo connectInfo = new ConnectInfo();
        connectInfo.setDbType("H2");
        connectInfo.setSchemaName(schemaName);
        DriverConfig driverConfig = new DriverConfig();
        driverConfig.setDbType("H2");
        connectInfo.setDriverConfig(driverConfig);
        Chat2DBContext.putContext(connectInfo);
    }

    @Test
    void connectDatabaseCatchesSQLExceptionForBadSchema() throws SQLException {
        // Set up context with a non-existent schema name
        putH2Context("NONEXISTENT_SCHEMA_12345");

        // Create an H2 in-memory connection
        try (Connection connection = DriverManager.getConnection("jdbc:h2:mem:test_h2db_manager")) {
            // connectDatabase should catch the SQLException from SET SCHEMA and not propagate it
            assertDoesNotThrow(() -> manager.connectDatabase(connection, "testdb"));
        }
    }

    @Test
    void connectDatabaseReturnsEarlyWhenSchemaNameIsBlank() throws SQLException {
        // Set up context with a blank schema name — method should return early
        putH2Context("");

        try (Connection connection = DriverManager.getConnection("jdbc:h2:mem:test_h2db_manager_blank")) {
            assertDoesNotThrow(() -> manager.connectDatabase(connection, "testdb"));
        }
    }
}

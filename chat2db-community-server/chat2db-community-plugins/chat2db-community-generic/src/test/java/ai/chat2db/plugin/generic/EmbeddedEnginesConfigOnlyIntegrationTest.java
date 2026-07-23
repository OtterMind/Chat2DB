package ai.chat2db.plugin.generic;

import ai.chat2db.community.domain.api.config.DBConfig;
import ai.chat2db.community.domain.api.config.DriverConfig;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves the configuration-only HSQLDB and Derby entries with in-memory
 * databases: the driver class each entry declares loads and serves
 * connect/DDL/DML/query plus the JDBC metadata path DefaultMetaService
 * relies on. Runs everywhere; no external service required.
 */
class EmbeddedEnginesConfigOnlyIntegrationTest {

    private String declaredDriverClass(String dbType) {
        return new GenericPlugin().getDBConfigList().stream()
                .filter(config -> dbType.equals(config.getDbType()))
                .findFirst()
                .map(DBConfig::getDefaultDriverConfig)
                .map(DriverConfig::getJdbcDriverClass)
                .orElseThrow();
    }

    private void smoke(String dbType, String memoryUrl, String tableName) throws Exception {
        Class.forName(declaredDriverClass(dbType));
        try (Connection connection = DriverManager.getConnection(memoryUrl)) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE " + tableName + " (ID INTEGER NOT NULL PRIMARY KEY, NAME VARCHAR(50))");
                assertEquals(1, statement.executeUpdate(
                        "INSERT INTO " + tableName + " (ID, NAME) VALUES (1, 'chat2db')"));
                try (ResultSet resultSet = statement.executeQuery(
                        "SELECT NAME FROM " + tableName + " WHERE ID = 1")) {
                    assertTrue(resultSet.next());
                    assertEquals("chat2db", resultSet.getString(1));
                }
            }
            DatabaseMetaData metaData = connection.getMetaData();
            boolean found = false;
            try (ResultSet tables = metaData.getTables(null, null, tableName, null)) {
                while (tables.next()) {
                    if (tableName.equals(tables.getString("TABLE_NAME"))) {
                        found = true;
                        break;
                    }
                }
            }
            assertTrue(found, dbType + " metadata should list the created table");
        }
    }

    @Test
    void hsqldbConfiguredDriverServesFullSmokePath() throws Exception {
        smoke("HSQLDB", "jdbc:hsqldb:mem:c2d_it;shutdown=true", "C2D_IT_HSQLDB");
    }

    @Test
    void derbyConfiguredDriverServesFullSmokePath() throws Exception {
        smoke("DERBY", "jdbc:derby:memory:c2d_it;create=true", "C2D_IT_DERBY");
    }
}

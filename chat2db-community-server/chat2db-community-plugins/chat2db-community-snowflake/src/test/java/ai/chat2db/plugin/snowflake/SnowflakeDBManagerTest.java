package ai.chat2db.plugin.snowflake;

import ai.chat2db.community.domain.api.config.DriverConfig;
import ai.chat2db.community.domain.api.model.datasource.KeyValue;
import ai.chat2db.spi.model.datasource.DriverEntry;
import ai.chat2db.spi.model.datasource.ConnectInfo;
import ai.chat2db.spi.sql.JdbcDriverManager;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;

class SnowflakeDBManagerTest {

    private final SnowflakeDBManager dbManager = new SnowflakeDBManager();

    @Test
    void reconnectReplacesManagedPropertiesInsteadOfAppendingDuplicates() {
        ConnectInfo connectInfo = new ConnectInfo();
        connectInfo.setDatabaseName("analytics");
        connectInfo.setSchemaName("reporting");
        connectInfo.setConnection(openConnectionStub());
        connectInfo.setExtendInfo(List.of(
                keyValue("role", "analyst"),
                keyValue("DB", "stale_database"),
                keyValue("schema", "stale_schema"),
                keyValue("jdbc_query_result_format", "ARROW")));

        assertSame(connectInfo.getConnection(), dbManager.getConnection(connectInfo));
        assertProperties(connectInfo.getExtendInfo(),
                List.of("role", "db", "schema", "JDBC_QUERY_RESULT_FORMAT"),
                List.of("analyst", "analytics", "reporting", "JSON"));

        assertSame(connectInfo.getConnection(), dbManager.getConnection(connectInfo));
        assertProperties(connectInfo.getExtendInfo(),
                List.of("role", "db", "schema", "JDBC_QUERY_RESULT_FORMAT"),
                List.of("analyst", "analytics", "reporting", "JSON"));
    }

    @Test
    void reconnectWithClearedDatabaseNameDropsStaleDatabaseProperty() {
        ConnectInfo connectInfo = new ConnectInfo();
        connectInfo.setDatabaseName("analytics");
        connectInfo.setSchemaName("reporting");
        connectInfo.setConnection(openConnectionStub());
        connectInfo.setExtendInfo(List.of(keyValue("role", "analyst")));

        assertSame(connectInfo.getConnection(), dbManager.getConnection(connectInfo));
        assertProperties(connectInfo.getExtendInfo(),
                List.of("role", "db", "schema", "JDBC_QUERY_RESULT_FORMAT"),
                List.of("analyst", "analytics", "reporting", "JSON"));

        connectInfo.setDatabaseName(null);
        assertSame(connectInfo.getConnection(), dbManager.getConnection(connectInfo));
        assertProperties(connectInfo.getExtendInfo(),
                List.of("role", "schema", "JDBC_QUERY_RESULT_FORMAT"),
                List.of("analyst", "reporting", "JSON"));
        assertFalse(connectInfo.getExtendMap().containsKey("db"));
    }

    @Test
    void reconnectWithClearedSchemaNameDropsStaleSchemaProperty() {
        ConnectInfo connectInfo = new ConnectInfo();
        connectInfo.setDatabaseName("analytics");
        connectInfo.setSchemaName("reporting");
        connectInfo.setConnection(openConnectionStub());
        connectInfo.setExtendInfo(List.of(keyValue("role", "analyst")));

        assertSame(connectInfo.getConnection(), dbManager.getConnection(connectInfo));
        assertProperties(connectInfo.getExtendInfo(),
                List.of("role", "db", "schema", "JDBC_QUERY_RESULT_FORMAT"),
                List.of("analyst", "analytics", "reporting", "JSON"));

        connectInfo.setSchemaName("");
        assertSame(connectInfo.getConnection(), dbManager.getConnection(connectInfo));
        assertProperties(connectInfo.getExtendInfo(),
                List.of("role", "db", "JDBC_QUERY_RESULT_FORMAT"),
                List.of("analyst", "analytics", "JSON"));
        assertFalse(connectInfo.getExtendMap().containsKey("schema"));
    }

    @Test
    void reconnectWithClearedDatabaseAndSchemaDropsStaleManagedProperties() {
        ConnectInfo connectInfo = new ConnectInfo();
        connectInfo.setDatabaseName("analytics");
        connectInfo.setSchemaName("reporting");
        connectInfo.setConnection(openConnectionStub());
        connectInfo.setExtendInfo(List.of(keyValue("role", "analyst")));

        assertSame(connectInfo.getConnection(), dbManager.getConnection(connectInfo));
        assertProperties(connectInfo.getExtendInfo(),
                List.of("role", "db", "schema", "JDBC_QUERY_RESULT_FORMAT"),
                List.of("analyst", "analytics", "reporting", "JSON"));

        connectInfo.setDatabaseName(" ");
        connectInfo.setSchemaName("");
        assertSame(connectInfo.getConnection(), dbManager.getConnection(connectInfo));
        assertProperties(connectInfo.getExtendInfo(),
                List.of("role", "JDBC_QUERY_RESULT_FORMAT"),
                List.of("analyst", "JSON"));
        assertFalse(connectInfo.getExtendMap().containsKey("db"));
        assertFalse(connectInfo.getExtendMap().containsKey("schema"));
    }

    @Test
    void passesDriverConfigPropertiesToNewConnectionWhenExtendInfoIsMissing() throws Exception {
        assertDriverConfigFallbackIsPassedToNewConnection(null);
        assertDriverConfigFallbackIsPassedToNewConnection(List.of());
    }

    private void assertDriverConfigFallbackIsPassedToNewConnection(List<KeyValue> configuredExtendInfo) throws Exception {
        String driverId = "snowflake-test-driver-" + UUID.randomUUID();
        CapturingDriver driver = new CapturingDriver(openConnectionStub());
        DriverConfig driverConfig = new DriverConfig();
        driverConfig.setJdbcDriver(driverId);
        driverConfig.setExtendInfo(List.of(
                keyValue("warehouse", "compute_wh"),
                keyValue("role", "analyst")));
        installDriver(driverConfig, driver);

        ConnectInfo connectInfo = new ConnectInfo();
        connectInfo.setUrl("jdbc:capturing:snowflake");
        connectInfo.setUser("test-user");
        connectInfo.setPassword("test-password");
        connectInfo.setExtendInfo(configuredExtendInfo);
        connectInfo.setDriverConfig(driverConfig);

        try {
            assertSame(driver.getConnection(), dbManager.getConnection(connectInfo));
            assertEquals("jdbc:capturing:snowflake", driver.getUrl());
            assertEquals("test-user", driver.getProperties().getProperty("user"));
            assertEquals("test-password", driver.getProperties().getProperty("password"));
            assertEquals("compute_wh", driver.getProperties().getProperty("warehouse"));
            assertEquals("analyst", driver.getProperties().getProperty("role"));
            assertEquals("JSON", driver.getProperties().getProperty("JDBC_QUERY_RESULT_FORMAT"));
            assertProperties(connectInfo.getExtendInfo(),
                    List.of("warehouse", "role", "JDBC_QUERY_RESULT_FORMAT"),
                    List.of("compute_wh", "analyst", "JSON"));
            assertEquals(List.of("warehouse", "role", "JDBC_QUERY_RESULT_FORMAT"),
                    new ArrayList<>(connectInfo.getExtendMap().keySet()));
            assertEquals(List.of("compute_wh", "analyst", "JSON"),
                    new ArrayList<>(connectInfo.getExtendMap().values()));
        } finally {
            JdbcDriverManager.unload(driverId);
        }
    }

    @SuppressWarnings("unchecked")
    private static void installDriver(DriverConfig driverConfig, Driver driver) throws ReflectiveOperationException {
        Field driverEntriesField = JdbcDriverManager.class.getDeclaredField("DRIVER_ENTRY_MAP");
        driverEntriesField.setAccessible(true);
        Map<String, DriverEntry> driverEntries = (Map<String, DriverEntry>) driverEntriesField.get(null);
        DriverEntry driverEntry = new DriverEntry();
        driverEntry.setDriverConfig(driverConfig);
        driverEntry.setDriver(driver);
        driverEntries.put(driverConfig.getJdbcDriver(), driverEntry);
    }

    private static Connection openConnectionStub() {
        return (Connection) Proxy.newProxyInstance(SnowflakeDBManagerTest.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                (proxy, method, args) -> {
                    if ("isClosed".equals(method.getName())) {
                        return false;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    private static KeyValue keyValue(String key, String value) {
        KeyValue keyValue = new KeyValue();
        keyValue.setKey(key);
        keyValue.setValue(value);
        return keyValue;
    }

    private static void assertProperties(List<KeyValue> properties, List<String> expectedKeys, List<String> expectedValues) {
        assertEquals(expectedKeys, properties.stream().map(KeyValue::getKey).toList());
        assertEquals(expectedValues, properties.stream().map(KeyValue::getValue).toList());
    }

    private static final class CapturingDriver implements Driver {
        private final Connection connection;
        private Properties properties;
        private String url;

        private CapturingDriver(Connection connection) {
            this.connection = connection;
        }

        @Override
        public Connection connect(String url, Properties info) {
            this.url = url;
            this.properties = new Properties();
            this.properties.putAll(info);
            return connection;
        }

        @Override
        public boolean acceptsURL(String url) {
            return true;
        }

        @Override
        public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) {
            return new DriverPropertyInfo[0];
        }

        @Override
        public int getMajorVersion() {
            return 1;
        }

        @Override
        public int getMinorVersion() {
            return 0;
        }

        @Override
        public boolean jdbcCompliant() {
            return false;
        }

        @Override
        public Logger getParentLogger() {
            return Logger.getLogger(CapturingDriver.class.getName());
        }

        private Connection getConnection() {
            return connection;
        }

        private Properties getProperties() {
            return properties;
        }

        private String getUrl() {
            return url;
        }
    }
}

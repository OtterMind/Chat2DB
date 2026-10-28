package ai.chat2db.plugin.snowflake;

import ai.chat2db.community.domain.api.config.DriverConfig;
import ai.chat2db.community.domain.api.model.datasource.KeyValue;
import ai.chat2db.spi.model.datasource.ConnectInfo;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SnowflakeDBManagerTest {

    @Test
    void preparesImmutablePropertiesIdempotently() {
        List<KeyValue> configured = List.of(
                keyValue("role", "analyst"),
                keyValue("DB", "stale_database"),
                keyValue("db", "duplicate_database"),
                keyValue("schema", "stale_schema"),
                keyValue("jdbc_query_result_format", "ARROW"));
        ConnectInfo connectInfo = connectInfo("analytics", "reporting", configured);

        List<KeyValue> first = SnowflakeDBManager.prepareExtendInfo(connectInfo);
        connectInfo.setExtendInfo(first);
        List<KeyValue> second = SnowflakeDBManager.prepareExtendInfo(connectInfo);

        assertProperties(first,
                List.of("role", "db", "schema", "JDBC_QUERY_RESULT_FORMAT"),
                List.of("analyst", "analytics", "reporting", "JSON"));
        assertProperties(second,
                List.of("role", "db", "schema", "JDBC_QUERY_RESULT_FORMAT"),
                List.of("analyst", "analytics", "reporting", "JSON"));
        assertProperties(configured,
                List.of("role", "DB", "db", "schema", "jdbc_query_result_format"),
                List.of("analyst", "stale_database", "duplicate_database", "stale_schema", "ARROW"));
    }

    @Test
    void dropsManagedPropertiesWhenContextIsCleared() {
        ConnectInfo connectInfo = connectInfo("analytics", "reporting", List.of(keyValue("role", "analyst")));
        connectInfo.setExtendInfo(SnowflakeDBManager.prepareExtendInfo(connectInfo));
        connectInfo.setDatabaseName(" ");
        connectInfo.setSchemaName(null);

        assertProperties(SnowflakeDBManager.prepareExtendInfo(connectInfo),
                List.of("role", "JDBC_QUERY_RESULT_FORMAT"),
                List.of("analyst", "JSON"));
    }

    @Test
    void fallsBackToDriverPropertiesWhenConfiguredPropertiesAreMissing() {
        DriverConfig driverConfig = new DriverConfig();
        driverConfig.setExtendInfo(List.of(
                keyValue("warehouse", "compute_wh"),
                keyValue("role", "analyst")));

        assertDriverFallback(null, driverConfig);
        assertDriverFallback(List.of(), driverConfig);
    }

    private static void assertDriverFallback(List<KeyValue> configured, DriverConfig driverConfig) {
        ConnectInfo connectInfo = connectInfo(null, null, configured);
        connectInfo.setDriverConfig(driverConfig);
        assertProperties(SnowflakeDBManager.prepareExtendInfo(connectInfo),
                List.of("warehouse", "role", "JDBC_QUERY_RESULT_FORMAT"),
                List.of("compute_wh", "analyst", "JSON"));
    }

    private static ConnectInfo connectInfo(String database, String schema, List<KeyValue> extendInfo) {
        ConnectInfo connectInfo = new ConnectInfo();
        connectInfo.setDatabaseName(database);
        connectInfo.setSchemaName(schema);
        connectInfo.setExtendInfo(extendInfo);
        return connectInfo;
    }

    private static KeyValue keyValue(String key, String value) {
        KeyValue keyValue = new KeyValue();
        keyValue.setKey(key);
        keyValue.setValue(value);
        return keyValue;
    }

    private static void assertProperties(List<KeyValue> properties, List<String> keys, List<String> values) {
        assertEquals(keys, properties.stream().map(KeyValue::getKey).toList());
        assertEquals(values, properties.stream().map(KeyValue::getValue).toList());
    }
}

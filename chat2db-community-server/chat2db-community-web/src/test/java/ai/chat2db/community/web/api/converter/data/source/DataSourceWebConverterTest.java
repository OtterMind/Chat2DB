package ai.chat2db.community.web.api.converter.data.source;

import ai.chat2db.community.web.api.model.request.data.source.DataSourceCreateRequest;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.sql.ResultSet;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DataSourceWebConverterTest {

    private ResultSet resultSet(Map<String, String> columns) {
        return (ResultSet) Proxy.newProxyInstance(
                ResultSet.class.getClassLoader(),
                new Class<?>[] {ResultSet.class},
                (proxy, method, args) -> {
                    if ("getString".equals(method.getName()) && args != null && args.length == 1) {
                        return columns.get((String) args[0]);
                    }
                    if ("toString".equals(method.getName())) {
                        return "stubResultSet";
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    private Map<String, String> baseRow() {
        Map<String, String> row = new HashMap<>();
        row.put("alias", "test");
        row.put("type", "MYSQL");
        row.put("environment_id", "1");
        return row;
    }

    @Test
    void mapsSslColumnOntoRequest() throws Exception {
        Map<String, String> row = baseRow();
        row.put("ssl", "{\"useSSL\":true}");

        DataSourceCreateRequest request = DataSourceWebConverter.INSTANCE.rs2Request(resultSet(row));

        assertNotNull(request.getSsl(), "ssl column from legacy row must be set on the request");
    }

    @Test
    void leavesSslNullWhenColumnBlank() throws Exception {
        DataSourceCreateRequest request = DataSourceWebConverter.INSTANCE.rs2Request(resultSet(baseRow()));

        assertNull(request.getSsl());
    }

    @Test
    void toleratesNullEnvironmentId() {
        Map<String, String> row = baseRow();
        row.put("environment_id", null);

        DataSourceCreateRequest request = assertDoesNotThrow(
                () -> DataSourceWebConverter.INSTANCE.rs2Request(resultSet(row)));

        assertNull(request.getEnvironmentId());
    }

    @Test
    void toleratesNonNumericEnvironmentId() {
        Map<String, String> row = baseRow();
        row.put("environment_id", "not-a-number");

        DataSourceCreateRequest request = assertDoesNotThrow(
                () -> DataSourceWebConverter.INSTANCE.rs2Request(resultSet(row)));

        assertNull(request.getEnvironmentId());
    }

    @Test
    void parsesValidEnvironmentId() throws Exception {
        Map<String, String> row = baseRow();
        row.put("environment_id", "3");

        DataSourceCreateRequest request = DataSourceWebConverter.INSTANCE.rs2Request(resultSet(row));

        assertEquals(3L, request.getEnvironmentId());
    }

    @Test
    void readsDriverConfigFromItsOwnColumn() throws Exception {
        Map<String, String> row = baseRow();
        row.put("ssh", "{\"use\":true,\"hostName\":\"ssh.example\"}");
        row.put("driver_config", "{\"jdbcDriver\":\"driver.jar\",\"jdbcDriverClass\":\"org.example.Driver\"}");

        DataSourceCreateRequest request = DataSourceWebConverter.INSTANCE.rs2Request(resultSet(row));

        assertTrue(request.getSsh().isUse());
        assertEquals("ssh.example", request.getSsh().getHostName());
        assertEquals("driver.jar", request.getDriverConfig().getJdbcDriver());
        assertEquals("org.example.Driver", request.getDriverConfig().getJdbcDriverClass());
    }
}

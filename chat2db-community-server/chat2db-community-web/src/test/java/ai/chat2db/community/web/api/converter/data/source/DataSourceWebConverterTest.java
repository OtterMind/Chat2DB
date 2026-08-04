package ai.chat2db.community.web.api.converter.data.source;

import ai.chat2db.community.web.api.model.request.data.source.DataSourceCreateRequest;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.sql.ResultSet;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DataSourceWebConverterTest {

    @Test
    void rs2RequestReadsDriverConfigFromItsOwnColumn() throws Exception {
        Map<String, String> columns = Map.of(
                "ssh", "{\"use\":true,\"hostName\":\"ssh.example\"}",
                "driver_config", "{\"jdbcDriver\":\"driver.jar\",\"jdbcDriverClass\":\"org.example.Driver\"}",
                "environment_id", "42");
        ResultSet resultSet = (ResultSet) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[] {ResultSet.class},
                (proxy, method, args) -> {
                    if ("getString".equals(method.getName())) {
                        return columns.get((String) args[0]);
                    }
                    if ("isWrapperFor".equals(method.getName())) {
                        return false;
                    }
                    if ("unwrap".equals(method.getName())) {
                        throw new UnsupportedOperationException();
                    }
                    return null;
                });

        DataSourceCreateRequest request = DataSourceWebConverter.INSTANCE.rs2Request(resultSet);

        assertTrue(request.getSsh().isUse());
        assertEquals("ssh.example", request.getSsh().getHostName());
        assertEquals("driver.jar", request.getDriverConfig().getJdbcDriver());
        assertEquals("org.example.Driver", request.getDriverConfig().getJdbcDriverClass());
        assertEquals(42L, request.getEnvironmentId());
    }
}

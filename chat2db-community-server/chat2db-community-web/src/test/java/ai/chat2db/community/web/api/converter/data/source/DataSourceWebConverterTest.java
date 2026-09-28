package ai.chat2db.community.web.api.converter.data.source;

import ai.chat2db.community.web.api.model.request.data.source.DataSourceCreateRequest;
import ai.chat2db.community.domain.api.config.Environment;
import ai.chat2db.community.domain.api.model.datasource.SSLInfo;
import ai.chat2db.community.domain.api.model.storage.WorkspaceDataSource;
import ai.chat2db.community.web.api.model.response.data.source.DataSourceIdentityColorResponse;
import ai.chat2db.community.web.api.model.response.data.source.DataSourceResponse;
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
        row.put("ssl", "{\"tlsMode\":\"VERIFY_CA\",\"trustStoreType\":\"JKS\","
                + "\"trustStoreBytes\":\"trust-base64\",\"trustStorePassword\":\"trustpass\","
                + "\"keyStoreType\":\"PKCS12\",\"keyStoreBytes\":\"client-base64\","
                + "\"keyStorePassword\":\"clientpass\"}");

        DataSourceCreateRequest request = DataSourceWebConverter.INSTANCE.rs2Request(resultSet(row));

        assertNotNull(request.getSsl(), "ssl column from legacy row must be set on the request");
        assertEquals("VERIFY_CA", request.getSsl().getTlsMode());
        assertEquals("JKS", request.getSsl().getTrustStoreType());
        assertEquals("trust-base64", request.getSsl().getTrustStoreBytes());
        assertEquals("trustpass", request.getSsl().getTrustStorePassword());
        assertEquals("PKCS12", request.getSsl().getKeyStoreType());
        assertEquals("client-base64", request.getSsl().getKeyStoreBytes());
        assertEquals("clientpass", request.getSsl().getKeyStorePassword());
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

    @Test
    void mapsIdentityColorFromCreateRequest() {
        DataSourceCreateRequest request = new DataSourceCreateRequest();
        request.setIdentityColor("#12AB34");

        DataSourceResponse response = DataSourceWebConverter.INSTANCE.request2response(request);

        assertEquals("#12AB34", response.getIdentityColor());
    }

    @Test
    void createResponseKeepsPublicSslFieldsAndRedactsSecrets() {
        DataSourceCreateRequest request = new DataSourceCreateRequest();
        SSLInfo ssl = new SSLInfo();
        ssl.setTlsMode("VERIFY_IDENTITY");
        ssl.setCaPem("ca");
        ssl.setClientCertPem("cert");
        ssl.setClientPrivateKeyPem("key");
        ssl.setClientKeyPassword("keypass");
        ssl.setTrustStoreType("JKS");
        ssl.setTrustStoreBytes("trust-store");
        ssl.setTrustStorePassword("trustpass");
        ssl.setKeyStoreType("PKCS12");
        ssl.setKeyStoreBytes("client-store");
        ssl.setKeyStorePassword("clientpass");
        request.setSsl(ssl);

        DataSourceResponse response = DataSourceWebConverter.INSTANCE.request2response(request);

        assertEquals("VERIFY_IDENTITY", response.getSsl().getTlsMode());
        assertEquals("JKS", response.getSsl().getTrustStoreType());
        assertEquals("PKCS12", response.getSsl().getKeyStoreType());
        assertNull(response.getSsl().getClientPrivateKeyPem());
        assertNull(response.getSsl().getClientKeyPassword());
        assertNull(response.getSsl().getTrustStoreBytes());
        assertNull(response.getSsl().getTrustStorePassword());
        assertNull(response.getSsl().getKeyStoreBytes());
        assertNull(response.getSsl().getKeyStorePassword());
        assertEquals("key", request.getSsl().getClientPrivateKeyPem());
        assertEquals("trust-store", request.getSsl().getTrustStoreBytes());
    }

    @Test
    void storageResponseRedactsSslSecretsWithoutRemovingPublicTlsFields() {
        WorkspaceDataSource dataSource = new WorkspaceDataSource();
        SSLInfo ssl = new SSLInfo();
        ssl.setTlsMode("VERIFY_IDENTITY");
        ssl.setCaPem("ca");
        ssl.setClientCertPem("cert");
        ssl.setClientPrivateKeyPem("key");
        ssl.setClientKeyPassword("keypass");
        ssl.setTrustStoreType("JKS");
        ssl.setTrustStoreBytes("trust-store");
        ssl.setTrustStorePassword("trustpass");
        ssl.setKeyStoreType("PKCS12");
        ssl.setKeyStoreBytes("client-store");
        ssl.setKeyStorePassword("clientpass");
        dataSource.setSsl(ssl);

        DataSourceResponse response = DataSourceWebConverter.INSTANCE.storage2response(dataSource);

        assertEquals("VERIFY_IDENTITY", response.getSsl().getTlsMode());
        assertEquals("ca", response.getSsl().getCaPem());
        assertEquals("cert", response.getSsl().getClientCertPem());
        assertEquals("JKS", response.getSsl().getTrustStoreType());
        assertEquals("PKCS12", response.getSsl().getKeyStoreType());
        assertNull(response.getSsl().getClientPrivateKeyPem());
        assertNull(response.getSsl().getClientKeyPassword());
        assertNull(response.getSsl().getTrustStoreBytes());
        assertNull(response.getSsl().getTrustStorePassword());
        assertNull(response.getSsl().getKeyStoreBytes());
        assertNull(response.getSsl().getKeyStorePassword());
    }

    @Test
    void mapsIdentityColorToDatasourceAndLightweightResponses() {
        Environment environment = Environment.builder()
                .id(2L)
                .name("RELEASE")
                .shortName("PROD")
                .color("RED")
                .build();
        WorkspaceDataSource dataSource = new WorkspaceDataSource();
        dataSource.setId(91L);
        dataSource.setIdentityColor("#ABCDEF");
        dataSource.setWatermarkEnabled(false);
        dataSource.setWatermarkContent("Finance Read Only");
        dataSource.setEnvironmentId(2L);
        dataSource.setEnvironment(environment);
        dataSource.setHost("sensitive.example");

        DataSourceResponse fullResponse = DataSourceWebConverter.INSTANCE.storage2response(dataSource);
        DataSourceIdentityColorResponse identityResponse =
                DataSourceWebConverter.INSTANCE.storage2identityColorResponse(dataSource);

        assertEquals("#ABCDEF", fullResponse.getIdentityColor());
        assertEquals(false, fullResponse.getWatermarkEnabled());
        assertEquals("Finance Read Only", fullResponse.getWatermarkContent());
        assertEquals(91L, identityResponse.getId());
        assertEquals("#ABCDEF", identityResponse.getIdentityColor());
        assertEquals(2L, identityResponse.getEnvironmentId());
        assertEquals("PROD", identityResponse.getEnvironment().getShortName());
    }
}

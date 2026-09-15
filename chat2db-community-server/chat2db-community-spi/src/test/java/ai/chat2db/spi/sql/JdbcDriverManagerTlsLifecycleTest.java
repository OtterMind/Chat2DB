package ai.chat2db.spi.sql;

import ai.chat2db.community.domain.api.config.DriverConfig;
import ai.chat2db.community.domain.api.enums.datasource.MySqlTlsMode;
import ai.chat2db.community.domain.api.model.datasource.SSLInfo;
import ai.chat2db.spi.model.datasource.DriverEntry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcDriverManagerTlsLifecycleTest {

    private String driverId;

    @AfterEach
    void removeTestDriver() throws Exception {
        if (driverId != null) {
            driverEntries().remove(driverId);
        }
    }

    @Test
    void successfulConnectionAttemptDeletesTranslatedTlsStores() throws Exception {
        Map<String, Object> properties = translatedTrustStore();
        Path store = temporaryStore(properties);
        AtomicInteger attempts = new AtomicInteger();
        DriverConfig config = registerDriver(() -> {
            attempts.incrementAndGet();
            assertTrue(Files.isRegularFile(store));
            return connection();
        });

        Connection connection = JdbcDriverManager.getConnection(
                "jdbc:mysql://localhost/test", "root", "password", config, properties);

        assertEquals(1, attempts.get());
        assertFalse(Files.exists(store));
        connection.close();
    }

    @Test
    void failedConnectionAttemptsDeleteTranslatedTlsStoresAfterRetry() throws Exception {
        Map<String, Object> properties = translatedTrustStore();
        Path store = temporaryStore(properties);
        AtomicInteger attempts = new AtomicInteger();
        DriverConfig config = registerDriver(() -> {
            attempts.incrementAndGet();
            assertTrue(Files.isRegularFile(store));
            throw new SQLException("TLS handshake failed");
        });

        assertThrows(SQLException.class, () -> JdbcDriverManager.getConnection(
                "jdbc:mysql://localhost/test", "root", "password", config, properties));

        assertEquals(2, attempts.get());
        assertFalse(Files.exists(store));
    }

    @Test
    void retryFailureMessagePassesThroughMysqlTlsDiagnostics() throws Exception {
        Map<String, Object> properties = translatedTrustStore();
        AtomicInteger attempts = new AtomicInteger();
        DriverConfig config = registerDriver(() -> {
            attempts.incrementAndGet();
            throw new SQLException("PKIX path building failed: unable to find valid certification path");
        });

        SQLException exception = assertThrows(SQLException.class, () -> JdbcDriverManager.getConnection(
                "jdbc:mysql://localhost/test", "root", "password", config, properties));

        assertEquals(2, attempts.get());
        assertTrue(exception.getMessage().contains("TLS certificate authority is not trusted"),
                exception.getMessage());
        assertTrue(exception.getMessage().contains("Upload the issuing CA PEM"), exception.getMessage());
    }

    @Test
    void rejectsNonJdbcAndControlCharacterUrlsBeforeConnecting() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        DriverConfig config = registerDriver(() -> {
            attempts.incrementAndGet();
            return connection();
        });

        assertThrows(SQLException.class, () -> JdbcDriverManager.getConnection(
                "https://metadata.invalid/latest", "root", "password", config));
        assertThrows(SQLException.class, () -> JdbcDriverManager.getConnection(
                "jdbc:mysql://localhost/test\nignored", "root", "password", config));

        assertEquals(0, attempts.get());
    }

    private DriverConfig registerDriver(Connector connector) throws Exception {
        driverId = "tls-lifecycle-" + UUID.randomUUID();
        DriverConfig config = new DriverConfig();
        config.setJdbcDriver(driverId);
        config.setJdbcDriverClass("test.Driver");
        driverEntries().put(driverId, DriverEntry.builder().driverConfig(config).driver(driver(connector)).build());
        return config;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, DriverEntry> driverEntries() throws Exception {
        Field field = JdbcDriverManager.class.getDeclaredField("DRIVER_ENTRY_MAP");
        field.setAccessible(true);
        return (Map<String, DriverEntry>) field.get(null);
    }

    private static Map<String, Object> translatedTrustStore() {
        SSLInfo ssl = new SSLInfo();
        ssl.setTlsMode(MySqlTlsMode.VERIFY_CA.name());
        ssl.setTrustStoreType("PKCS12");
        ssl.setTrustStoreBytes(base64KeyStore());
        Map<String, Object> properties = new HashMap<>();
        MySqlTlsTranslator.apply(ssl, null, properties);
        return properties;
    }

    private static Path temporaryStore(Map<String, Object> properties) throws Exception {
        return Path.of(new URL((String) properties.get("trustCertificateKeyStoreUrl")).toURI());
    }

    private static String base64KeyStore() {
        try {
            KeyStore keyStore = KeyStore.getInstance("PKCS12");
            keyStore.load(null, null);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            keyStore.store(output, null);
            return Base64.getEncoder().encodeToString(output.toByteArray());
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }

    private static Driver driver(Connector connector) {
        return new Driver() {
            @Override
            public Connection connect(String url, Properties info) throws SQLException {
                return connector.connect();
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
                return Logger.getGlobal();
            }
        };
    }

    private static Connection connection() {
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                    if ("isClosed".equals(method.getName())) {
                        return false;
                    }
                    if (method.getReturnType().isPrimitive()) {
                        return method.getReturnType() == boolean.class ? false : 0;
                    }
                    return null;
                });
    }

    @FunctionalInterface
    private interface Connector {
        Connection connect() throws SQLException;
    }
}

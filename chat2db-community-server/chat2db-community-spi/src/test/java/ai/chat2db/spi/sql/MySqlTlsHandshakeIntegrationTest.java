package ai.chat2db.spi.sql;

import ai.chat2db.community.domain.api.config.DriverConfig;
import ai.chat2db.community.domain.api.enums.datasource.MySqlTlsMode;
import ai.chat2db.community.domain.api.model.datasource.SSLInfo;
import ai.chat2db.community.tools.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Opt-in fixture for the review-required real MySQL TLS handshake path.
 *
 * Configure a local TLS-enabled MySQL and run with:
 * CHAT2DB_MYSQL_TLS_URL=jdbc:mysql://127.0.0.1:3306/mysql \
 * CHAT2DB_MYSQL_TLS_USER=root \
 * CHAT2DB_MYSQL_TLS_PASSWORD=secret \
 * CHAT2DB_MYSQL_TLS_CA_PEM_FILE=/path/to/ca.pem \
 * CHAT2DB_MYSQL_TLS_CONNECTOR_JAR=$HOME/.m2/repository/com/mysql/mysql-connector-j/8.0.33/mysql-connector-j-8.0.33.jar \
 * mvn -B -f chat2db-community-server/pom.xml -pl :chat2db-community-spi -am \
 *   -Dmaven.test.skip=false -DskipTests=false -Dtest=MySqlTlsHandshakeIntegrationTest \
 *   -Dsurefire.failIfNoSpecifiedTests=false -Dmaven.test.failure.ignore=false test
 *
 * The default suite skips when the fixture is not configured, matching the repository's existing
 * container-backed integration-test pattern.
 */
class MySqlTlsHandshakeIntegrationTest {

    private static final String URL_ENV = "CHAT2DB_MYSQL_TLS_URL";
    private static final String USER_ENV = "CHAT2DB_MYSQL_TLS_USER";
    private static final String PASSWORD_ENV = "CHAT2DB_MYSQL_TLS_PASSWORD";
    private static final String CA_PEM_ENV = "CHAT2DB_MYSQL_TLS_CA_PEM";
    private static final String CA_PEM_FILE_ENV = "CHAT2DB_MYSQL_TLS_CA_PEM_FILE";
    private static final String CONNECTOR_JAR_ENV = "CHAT2DB_MYSQL_TLS_CONNECTOR_JAR";
    private static final String DRIVER_CLASS_ENV = "CHAT2DB_MYSQL_TLS_DRIVER_CLASS";
    private static final String TLS_MODE_ENV = "CHAT2DB_MYSQL_TLS_MODE";

    @Test
    void structuredTlsPropertiesCompleteRealMysqlHandshakeWhenConfigured() throws Exception {
        String jdbcUrl = env(URL_ENV);
        assumeTrue(jdbcUrl != null, URL_ENV + " not set; skipping real MySQL TLS fixture");
        Path connectorJar = connectorJar();
        assumeTrue(Files.isRegularFile(connectorJar), CONNECTOR_JAR_ENV + " does not point to a driver jar");

        HostPort hostPort = parseHostPort(jdbcUrl);
        assumeTrue(hostPort == null || reachable(hostPort.host(), hostPort.port()),
                "MySQL TLS fixture is not reachable; skipping");

        DriverConfig driverConfig = new DriverConfig();
        driverConfig.setJdbcDriverClass(envOrDefault(DRIVER_CLASS_ENV, "com.mysql.cj.jdbc.Driver"));
        driverConfig.setJdbcDriver(connectorJar.getFileName().toString());

        Map<String, Object> propertyMap = new LinkedHashMap<>();
        putIfPresent(propertyMap, "user", env(USER_ENV));
        putIfPresent(propertyMap, "password", env(PASSWORD_ENV));
        MySqlTlsTranslator.apply(ssl(), driverConfig, propertyMap);
        Properties properties = jdbcProperties(propertyMap);

        try (URLClassLoader loader = new URLClassLoader(new URL[]{connectorJar.toUri().toURL()},
                ClassLoader.getPlatformClassLoader());
             Connection connection = driver(loader, driverConfig.getJdbcDriverClass()).connect(jdbcUrl, properties)) {
            assertNotNull(connection, "Connector/J should accept the structured TLS properties");
            assertTrue(connection.isValid(5), "TLS MySQL connection should validate");
            assertFalse(mysqlSslCipher(connection).isBlank(), "MySQL session must report an active TLS cipher");
        }
    }

    @Test
    void configuredConnectorJarVersionSelectsExpectedTlsDialectWithoutSecrets() throws Exception {
        Path connectorJar = connectorJar();
        assumeTrue(Files.isRegularFile(connectorJar), CONNECTOR_JAR_ENV + " does not point to a driver jar");

        DriverConfig driverConfig = new DriverConfig();
        driverConfig.setJdbcDriverClass(envOrDefault(DRIVER_CLASS_ENV, "com.mysql.cj.jdbc.Driver"));
        driverConfig.setJdbcDriver(connectorJar.getFileName().toString());

        try (URLClassLoader loader = new URLClassLoader(new URL[]{connectorJar.toUri().toURL()},
                ClassLoader.getPlatformClassLoader())) {
            Driver driver = driver(loader, driverConfig.getJdbcDriverClass());
            Map<String, Object> properties = new LinkedHashMap<>();

            if (driver.getMajorVersion() < 8) {
                BusinessException exception = assertThrows(BusinessException.class,
                        () -> MySqlTlsTranslator.apply(ssl(MySqlTlsMode.VERIFY_IDENTITY), driverConfig, properties));
                assertTrue(exception.getCode().contains("verifyIdentityUnsupportedConnectorJ5"));
                assertTrue(properties.isEmpty());
                return;
            }

            MySqlTlsTranslator.apply(ssl(MySqlTlsMode.VERIFY_IDENTITY), driverConfig, properties);
            assertTrue(driver.getMajorVersion() >= 8, "Connector/J 8+ should use sslMode");
            assertTrue(properties.containsKey("sslMode"));
            assertFalse(properties.containsKey("useSSL"));
        }
    }

    private static SSLInfo ssl() throws IOException {
        SSLInfo ssl = new SSLInfo();
        ssl.setTlsMode(envOrDefault(TLS_MODE_ENV, MySqlTlsMode.VERIFY_CA.name()));
        String caPem = env(CA_PEM_ENV);
        String caPemFile = env(CA_PEM_FILE_ENV);
        if (caPem == null && caPemFile != null) {
            caPem = Files.readString(Path.of(caPemFile));
        }
        ssl.setCaPem(caPem);
        return ssl;
    }

    private static SSLInfo ssl(MySqlTlsMode mode) {
        SSLInfo ssl = new SSLInfo();
        ssl.setTlsMode(mode.name());
        return ssl;
    }

    private static Driver driver(ClassLoader loader, String driverClass) throws Exception {
        return (Driver) Class.forName(driverClass, true, loader).getDeclaredConstructor().newInstance();
    }

    private static String mysqlSslCipher(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SHOW STATUS LIKE 'Ssl_cipher'")) {
            assertTrue(resultSet.next(), "SHOW STATUS should return Ssl_cipher");
            String cipher = resultSet.getString(2);
            return cipher == null ? "" : cipher;
        }
    }

    private static Path connectorJar() {
        String configured = env(CONNECTOR_JAR_ENV);
        if (configured != null) {
            return Path.of(configured);
        }
        return Path.of(System.getProperty("user.home"), ".m2", "repository", "com", "mysql", "mysql-connector-j",
                "8.0.33", "mysql-connector-j-8.0.33.jar");
    }

    private static void putIfPresent(Map<String, Object> properties, String key, String value) {
        if (value != null) {
            properties.put(key, value);
        }
    }

    private static Properties jdbcProperties(Map<String, Object> propertyMap) {
        Properties properties = new Properties();
        propertyMap.forEach((key, value) -> {
            if (value != null) {
                properties.setProperty(key, value.toString());
            }
        });
        return properties;
    }

    private static String envOrDefault(String name, String defaultValue) {
        String value = env(name);
        return value == null ? defaultValue : value;
    }

    private static String env(String name) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? null : value;
    }

    private static boolean reachable(String host, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 1500);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static HostPort parseHostPort(String jdbcUrl) {
        int marker = jdbcUrl.indexOf("//");
        if (marker < 0) {
            return null;
        }
        int hostStart = marker + 2;
        int hostEnd = jdbcUrl.indexOf('/', hostStart);
        String authority = hostEnd < 0 ? jdbcUrl.substring(hostStart) : jdbcUrl.substring(hostStart, hostEnd);
        int params = authority.indexOf('?');
        if (params >= 0) {
            authority = authority.substring(0, params);
        }
        if (authority.isBlank() || authority.contains(",")) {
            return null;
        }
        String host = authority;
        int port = 3306;
        int colon = authority.lastIndexOf(':');
        if (colon > 0 && colon < authority.length() - 1) {
            host = authority.substring(0, colon);
            try {
                port = Integer.parseInt(authority.substring(colon + 1));
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return new HostPort(host, port);
    }

    private record HostPort(String host, int port) {
    }
}

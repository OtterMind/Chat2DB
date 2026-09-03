package ai.chat2db.spi.sql;

import ai.chat2db.community.domain.api.config.DriverConfig;
import ai.chat2db.community.domain.api.model.request.datasource.DbDataSourcePreConnectRequest;
import ai.chat2db.community.tools.constant.JdbcDriverConstants;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverPropertyInfo;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class HsqldbTrustedDriverDownloadTest {

    private static final String HSQLDB_JAR = "hsqldb-2.7.3.jar";
    private static final String HSQLDB_DRIVER_CLASS = "org.hsqldb.jdbc.JDBCDriver";
    private static final String TRUSTED_HSQLDB_URL =
            "https://repo1.maven.org/maven2/org/hsqldb/hsqldb/2.7.3/hsqldb-2.7.3.jar";
    private static final String MALICIOUS_URL = "http://127.0.0.1:1/hsqldb-2.7.3.jar";

    @TempDir
    private Path temporaryDirectory;

    @Test
    void preConnectRequestCannotOverrideTheTrustedDriverDownloadUrl() {
        DriverConfig requestDriver = new DriverConfig();
        requestDriver.setJdbcDriver(HSQLDB_JAR);
        requestDriver.setJdbcDriverClass(HSQLDB_DRIVER_CLASS);
        requestDriver.setDownloadJdbcDriverUrls(List.of(MALICIOUS_URL));
        DbDataSourcePreConnectRequest request = new DbDataSourcePreConnectRequest();
        request.setType("HSQLDB");
        request.setDriverConfig(requestDriver);

        List<String> selectedUrls = JdbcDriverManager.resolveTrustedDownloadUrls(
                request.getType(), request.getDriverConfig());

        assertEquals(List.of(TRUSTED_HSQLDB_URL), selectedUrls);
        assertFalse(selectedUrls.contains(MALICIOUS_URL));
    }

    @Test
    void requestThatDoesNotExactlyMatchBuiltInDriverGetsNoTrustedUrls() {
        DriverConfig requestDriver = new DriverConfig();
        requestDriver.setJdbcDriver(HSQLDB_JAR);
        requestDriver.setJdbcDriverClass("attacker.Driver");
        requestDriver.setDownloadJdbcDriverUrls(List.of(MALICIOUS_URL));

        assertEquals(List.of(), JdbcDriverManager.resolveTrustedDownloadUrls("HSQLDB", requestDriver));
    }

    @Test
    void legacyPublicApisKeepUsingTheTrustedBuiltInDriverConfig() throws Exception {
        String originalHome = System.getProperty("user.home");
        String originalRuntimeMode = System.getProperty("chat2db.runtime.mode");
        DriverConfig builtInDriver = Chat2DBContext.getDefaultDriverConfig("HSQLDB");
        try {
            System.setProperty("chat2db.runtime.mode", "community");
            System.setProperty("user.home", temporaryDirectory.toString());
            Path sourceJar = Path.of(new URI(org.hsqldb.jdbc.JDBCDriver.class.getProtectionDomain()
                    .getCodeSource().getLocation().toString()));
            Path targetJar = JdbcDriverConstants.createDriverLibDirectory().toPath().resolve(HSQLDB_JAR);
            Files.copy(sourceJar, targetJar, StandardCopyOption.REPLACE_EXISTING);
            JdbcDriverManager.unload(HSQLDB_JAR);

            assertEquals("HSQLDB", builtInDriver.getDbType());
            assertEquals(List.of(TRUSTED_HSQLDB_URL), JdbcDriverManager.resolveTrustedDownloadUrls(
                    builtInDriver.getDbType(), builtInDriver));
            assertNotNull(JdbcDriverManager.getClassLoader(builtInDriver).loadClass(HSQLDB_DRIVER_CLASS));
            DriverPropertyInfo[] properties = JdbcDriverManager.getProperty(builtInDriver);
            assertNotNull(properties);
            try (Connection connection = JdbcDriverManager.getConnection(
                    "jdbc:hsqldb:mem:legacy_driver_api;shutdown=true", builtInDriver)) {
                assertFalse(connection.isClosed());
            }
        } finally {
            JdbcDriverManager.unload(HSQLDB_JAR);
            restoreProperty("user.home", originalHome);
            restoreProperty("chat2db.runtime.mode", originalRuntimeMode);
        }
    }

    private void restoreProperty(String name, String value) {
        if (value == null) {
            System.clearProperty(name);
        } else {
            System.setProperty(name, value);
        }
    }
}

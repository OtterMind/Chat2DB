package ai.chat2db.plugin.mysql.account;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MysqlSec002FixtureTest {

    @Test
    void fixtureSupportsVersionAwarePluginsAndRepeatableTlsConnectionChecks() throws IOException {
        Path fixture = fixtureDirectory();
        String init = Files.readString(fixture.resolve("init.sql"));
        String grants = Files.readString(fixture.resolve("grants.sql"));
        String readme = Files.readString(fixture.resolve("README.md"));
        String verifier = Files.readString(fixture.resolve("verify-connections.sh"));

        assertTrue(init.contains("PLUGIN_STATUS = 'ACTIVE'"), init);
        assertTrue(init.contains("PREPARE sec002_native_stmt"), init);
        assertTrue(init.contains("REQUIRE ISSUER '/CN=Chat2DB Test CA'"), init);
        assertFalse(grants.contains("SYSTEM_USER"), grants);
        assertTrue(readme.contains("./verify-connections.sh"), readme);
        assertTrue(readme.contains("CURRENT_USER()"), readme);
        assertTrue(verifier.contains("--ssl-mode=\"$ssl_mode\""), verifier);
        assertTrue(verifier.contains("client-cert.pem"), verifier);

        Path tls = fixture.resolve("tls");
        assertTrue(Files.exists(tls.resolve("generate-certs.sh")));
        assertTrue(Files.exists(tls.resolve("my.cnf.example")));
        assertFalse(Files.exists(tls.resolve("generated/client-key.pem")),
                "generated private keys must not be versioned");
    }

    private static Path fixtureDirectory() {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (current != null) {
            Path candidate = current.resolve("script/test-fixtures/mysql/MYSQL-SEC-002");
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
            current = current.getParent();
        }
        throw new AssertionError("Could not locate MYSQL-SEC-002 fixture");
    }
}

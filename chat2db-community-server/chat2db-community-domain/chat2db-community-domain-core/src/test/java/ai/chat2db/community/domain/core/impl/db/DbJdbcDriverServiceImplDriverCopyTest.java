package ai.chat2db.community.domain.core.impl.db;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class DbJdbcDriverServiceImplDriverCopyTest {

    @TempDir
    private Path temporaryDirectory;

    @Test
    void copyDriversCreatesDirectoryUnderTheCurrentUserHome() throws Exception {
        String originalHome = System.getProperty("user.home");
        String originalRuntimeMode = System.getProperty("chat2db.runtime.mode");
        byte[] driverBytes = "custom-driver".getBytes(StandardCharsets.UTF_8);
        Path source = temporaryDirectory.resolve("custom-driver.jar");
        Files.write(source, driverBytes);
        Path initializedHome = temporaryDirectory.resolve("initialized-home");
        Path activeHome = temporaryDirectory.resolve("active-home");
        try {
            System.setProperty("chat2db.runtime.mode", "community");
            System.setProperty("user.home", initializedHome.toString());
            DbJdbcDriverServiceImpl service = new DbJdbcDriverServiceImpl();

            System.setProperty("user.home", activeHome.toString());
            Path target = activeHome.resolve(".chat2db-community").resolve("jdbc-lib")
                    .resolve("custom-driver.jar");
            assertFalse(Files.exists(target.getParent()));

            assertEquals("custom-driver.jar", service.copyDrivers(List.of(source.toString())));
            assertArrayEquals(driverBytes, Files.readAllBytes(target));
        } finally {
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

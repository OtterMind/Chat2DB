package ai.chat2db.community.updater.v2.telemetry;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TelemetryConfigTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void storeFileLivesInTheProvidedConfigDirectory() {
        TelemetryConfig.configDirectory(temporaryDirectory.resolve("config").toString());

        assertEquals(temporaryDirectory.resolve("config").resolve("device_id.json"), TelemetryConfig.storeFile());
    }
}

package ai.chat2db.community.updater.v2.telemetry;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TelemetryStoreTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void deviceIdIsWrittenOnceAndReused() throws Exception {
        Path file = temporaryDirectory.resolve("config").resolve("device_id.json");

        String first = new TelemetryStore(file).deviceId();
        String second = new TelemetryStore(file).deviceId();

        assertEquals(first, second, "a restart must reuse the stored device id");
        assertTrue(first.startsWith("d1_") || first.startsWith("r1_"), first);
        assertTrue(Files.readString(file).contains("\"deviceId\""), Files.readString(file));
        assertTrue(Files.readString(file).contains(first));
    }

    @Test
    void deviceIdStaysStableWhenTheFileCannotBeWritten() throws Exception {
        // A directory at the file path makes every write fail.
        Path file = temporaryDirectory.resolve("config").resolve("device_id.json");
        Files.createDirectories(file);

        TelemetryStore store = new TelemetryStore(file);

        assertEquals(store.deviceId(), store.deviceId());
    }

    @Test
    void sessionCacheStaysInMemory() {
        TelemetryStore store = new TelemetryStore(temporaryDirectory.resolve("device_id.json"));

        store.saveCache("cache-1");

        assertEquals("cache-1", store.cache());
        assertEquals("", new TelemetryStore(temporaryDirectory.resolve("device_id.json")).cache());
    }
}

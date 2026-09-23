package ai.chat2db.community.updater.v2.telemetry;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * On disk state for the desktop usage reporting: the device id, kept in the product config directory.
 *
 * <p>The Umami session token the server returns is only kept in memory: a new application start is a
 * new visit, and the session itself is derived from the device, the user agent and the month.</p>
 */
public final class TelemetryStore {

    private final Path file;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private volatile String sessionCache = "";

    private volatile String deviceId;

    public TelemetryStore() {
        this.file = null;
    }

    public TelemetryStore(Path file) {
        this.file = file;
    }

    public synchronized String deviceId() {
        String known = deviceId;
        if (known != null && !known.isBlank()) {
            return known;
        }
        String stored = read();
        String resolved = stored == null || stored.isBlank() ? DeviceIdProvider.resolve() : stored;
        // Remember it for the process even when the file cannot be written, so every report of this
        // run carries the same device.
        deviceId = resolved;
        if (stored == null || stored.isBlank()) {
            write(resolved);
        }
        return resolved;
    }

    public String cache() {
        return sessionCache;
    }

    public void saveCache(String cache) {
        if (cache != null && !cache.isBlank()) {
            sessionCache = cache;
        }
    }

    private Path file() {
        return file != null ? file : TelemetryConfig.storeFile();
    }

    private String read() {
        Path path = file();
        if (path == null || !Files.isRegularFile(path)) {
            return null;
        }
        try {
            State state = objectMapper.readValue(path.toFile(), State.class);
            return state == null ? null : state.deviceId();
        } catch (IOException exception) {
            return null;
        }
    }

    private void write(String deviceId) {
        Path path = file();
        if (path == null) {
            return;
        }
        try {
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            objectMapper.writeValue(path.toFile(), new State(deviceId));
        } catch (IOException ignored) {
            // Reporting state is best effort.
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record State(String deviceId) {
    }
}

package ai.chat2db.community.tools.util;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

final class SettingsFileStore {

    private final Path filePath;
    private final ObjectMapper objectMapper;

    SettingsFileStore(Path filePath) {
        this.filePath = filePath;
        this.objectMapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    }

    synchronized Object getProperty(String key) {
        return readSettings().get(key);
    }

    synchronized boolean getBooleanProperty(String key, boolean defaultValue) {
        Object value = readSettings().get(key);
        if (value instanceof Boolean booleanValue) {
            return booleanValue;
        }
        if (value instanceof String stringValue) {
            return Boolean.parseBoolean(stringValue);
        }
        return defaultValue;
    }

    synchronized void setProperty(String key, Object newValue) {
        Objects.requireNonNull(key, "settings key must not be null");
        Objects.requireNonNull(newValue, "settings value must not be null");
        Map<String, Object> settings = readSettings();
        settings.put(key, newValue);
        writeSettings(settings);
    }

    synchronized String getOrCreateToken(String key) {
        Map<String, Object> settings = readSettings();
        Object value = settings.get(key);
        if (value instanceof String token && !token.isBlank()) {
            return token;
        }
        String token = newToken();
        settings.put(key, token);
        writeSettings(settings);
        return token;
    }

    synchronized String resetToken(String key) {
        Map<String, Object> settings = readSettings();
        String token = newToken();
        settings.put(key, token);
        writeSettings(settings);
        return token;
    }

    private Map<String, Object> readSettings() {
        if (!Files.exists(filePath)) {
            return new HashMap<>();
        }
        try {
            String json = Files.readString(filePath, StandardCharsets.UTF_8);
            if (json.isBlank()) {
                return new HashMap<>();
            }
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {
            });
        } catch (IOException exception) {
            throw new UncheckedIOException("Failed to read settings from " + filePath, exception);
        }
    }

    private void writeSettings(Map<String, Object> settings) {
        Path parent = filePath.getParent();
        Path temporaryFile = null;
        try {
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path temporaryDirectory = parent == null ? Path.of(".") : parent;
            temporaryFile = Files.createTempFile(temporaryDirectory, filePath.getFileName().toString(), ".tmp");
            Files.writeString(temporaryFile, objectMapper.writeValueAsString(settings), StandardCharsets.UTF_8);
            moveIntoPlace(temporaryFile);
        } catch (IOException exception) {
            throw new UncheckedIOException("Failed to write settings to " + filePath, exception);
        } finally {
            if (temporaryFile != null) {
                try {
                    Files.deleteIfExists(temporaryFile);
                } catch (IOException ignored) {
                    // The committed file has already been moved; stale temp cleanup is best effort.
                }
            }
        }
    }

    private void moveIntoPlace(Path temporaryFile) throws IOException {
        try {
            Files.move(temporaryFile, filePath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temporaryFile, filePath, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String newToken() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}

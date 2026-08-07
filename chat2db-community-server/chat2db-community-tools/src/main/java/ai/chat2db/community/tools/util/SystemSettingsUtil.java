package ai.chat2db.community.tools.util;

import ai.chat2db.community.tools.config.SystemSettingConstant;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Slf4j
public class SystemSettingsUtil {

    private static final ObjectMapper OBJECT_MAPPER;
    private static final String CACHE_PATH;

    static {
        OBJECT_MAPPER = new ObjectMapper();
        OBJECT_MAPPER.enable(SerializationFeature.INDENT_OUTPUT);
        if (ConfigUtils.isCommunity()) {
            CACHE_PATH = "chat2db_cache_community";
        } else if (ConfigUtils.isLocalEdition()) {
            CACHE_PATH = "chat2db_cache_local";
        } else {
            CACHE_PATH = "chat2db_cache_pro";
        }
    }

    private SystemSettingsUtil() {
    }

    public static String getCachePath() {
        return ConfigUtils.getBasePath() + File.separator + CACHE_PATH;
    }

    public static synchronized void setProperty(String key, Object newValue) {
        if (Objects.isNull(key) || Objects.isNull(newValue)) {
            log.info("key or new value is null");
            return;
        }
        Map<String, Object> settings = readSettings();
        settings.put(key, newValue);
        writeSettings(settings);
    }

    public static Object getProperty(String key) {
        Map<String, Object> settings = readSettings();
        return settings.get(key);
    }

    public static boolean getBooleanProperty(String key, boolean defaultValue) {
        Object value = getProperty(key);
        if (value instanceof Boolean booleanValue) {
            return booleanValue;
        }
        if (value instanceof String stringValue) {
            return Boolean.parseBoolean(stringValue);
        }
        return defaultValue;
    }

    public static boolean isMcpEnabled() {
        return getBooleanProperty(SystemSettingConstant.ENABLE_MCP, false);
    }

    public static String getOrCreateMcpAuthToken() {
        Object value = getProperty(SystemSettingConstant.MCP_AUTH_TOKEN);
        if (value instanceof String token && !token.isBlank()) {
            return token;
        }
        String token = UUID.randomUUID().toString().replace("-", "");
        setProperty(SystemSettingConstant.MCP_AUTH_TOKEN, token);
        return token;
    }

    public static String resetMcpAuthToken() {
        String token = UUID.randomUUID().toString().replace("-", "");
        setProperty(SystemSettingConstant.MCP_AUTH_TOKEN, token);
        return token;
    }

    private static void writeSettings(Map<String, Object> settings) {
        Path filePath = Paths.get(getCachePath(), "settings.json");
        log.info("Jackson: preparing to write settings file to: {}", filePath);
        try {
            Path parentDir = filePath.getParent();
            if (parentDir != null && !Files.exists(parentDir)) {
                Files.createDirectories(parentDir);
            }
            String jsonString = OBJECT_MAPPER.writeValueAsString(settings);
            Files.writeString(filePath, jsonString, StandardCharsets.UTF_8);
            log.info("Jackson: settings file written.");
        } catch (IOException e) {
            log.error("Jackson: failed to write settings file", e);
        }
    }

    private static Map<String, Object> readSettings() {
        Path filePath = Paths.get(getCachePath(), "settings.json");
        log.info("Jackson: preparing to read settings file from: {}", filePath);
        if (!Files.exists(filePath)) {
            log.info("Settings file does not exist, returning empty settings.");
            return new HashMap<>();
        }

        try {
            String jsonString = Files.readString(filePath, StandardCharsets.UTF_8);
            if (jsonString.isBlank()) {
                log.info("Settings file is empty, returning empty settings.");
                return new HashMap<>();
            }
            Map<String, Object> settings = OBJECT_MAPPER.readValue(jsonString, new TypeReference<>() {
            });
            log.info("Jackson: settings file read and parsed.");
            return settings;
        } catch (IOException e) {
            log.error("Jackson: failed to read or parse settings file", e);
        }
        return new HashMap<>();
    }
}

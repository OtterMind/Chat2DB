package ai.chat2db.community.tools.util;

import ai.chat2db.community.tools.config.SystemSettingConstant;
import java.io.File;
import java.nio.file.Paths;

public class SystemSettingsUtil {

    private static final String CACHE_PATH;
    private static final SettingsFileStore SETTINGS_STORE;

    static {
        if (ConfigUtils.isCommunity()) {
            CACHE_PATH = "chat2db_cache_community";
        } else if (ConfigUtils.isLocalEdition()) {
            CACHE_PATH = "chat2db_cache_local";
        } else {
            CACHE_PATH = "chat2db_cache_pro";
        }
        SETTINGS_STORE = new SettingsFileStore(Paths.get(getCachePath(), "settings.json"));
    }

    private SystemSettingsUtil() {
    }

    public static String getCachePath() {
        return ConfigUtils.getBasePath() + File.separator + CACHE_PATH;
    }

    public static void setProperty(String key, Object newValue) {
        SETTINGS_STORE.setProperty(key, newValue);
    }

    public static Object getProperty(String key) {
        return SETTINGS_STORE.getProperty(key);
    }

    public static boolean getBooleanProperty(String key, boolean defaultValue) {
        return SETTINGS_STORE.getBooleanProperty(key, defaultValue);
    }

    public static boolean isMcpEnabled() {
        return getBooleanProperty(SystemSettingConstant.ENABLE_MCP, false);
    }

    public static String getOrCreateMcpAuthToken() {
        return SETTINGS_STORE.getOrCreateToken(SystemSettingConstant.MCP_AUTH_TOKEN);
    }

    public static String resetMcpAuthToken() {
        return SETTINGS_STORE.resetToken(SystemSettingConstant.MCP_AUTH_TOKEN);
    }
}

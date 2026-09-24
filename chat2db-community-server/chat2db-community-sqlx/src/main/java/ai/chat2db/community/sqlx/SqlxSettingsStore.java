package ai.chat2db.community.sqlx;

import ai.chat2db.community.tools.util.SystemSettingsUtil;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Storage for the small amount of state the SQLX page keeps between restarts.
 */
public interface SqlxSettingsStore {

    String get(String key);

    void set(String key, String value);

    /** Desktop settings file shared with the rest of Chat2DB. */
    static SqlxSettingsStore system() {
        return new SqlxSettingsStore() {
            @Override
            public String get(String key) {
                Object value = SystemSettingsUtil.getProperty(key);
                return value == null ? null : String.valueOf(value);
            }

            @Override
            public void set(String key, String value) {
                SystemSettingsUtil.setProperty(key, value);
            }
        };
    }

    /** In-memory store for tests. */
    static SqlxSettingsStore inMemory(Map<String, String> values) {
        Map<String, String> state = new ConcurrentHashMap<>(values);
        return new SqlxSettingsStore() {
            @Override
            public String get(String key) {
                return state.get(key);
            }

            @Override
            public void set(String key, String value) {
                state.put(key, value);
            }
        };
    }
}

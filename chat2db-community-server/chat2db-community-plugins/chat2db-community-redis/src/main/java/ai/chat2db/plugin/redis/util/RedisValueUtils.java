package ai.chat2db.plugin.redis.util;

public class RedisValueUtils {

    public static String getRedisValue(String value) {
        if (value == null) {
            return null;
        }
        if (value.indexOf('\0') >= 0 || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
            throw new IllegalArgumentException("Redis JDBC command arguments cannot contain NUL, CR, or LF");
        }
        if (value.contains("\\")) {
            value = value.replace("\\", "\\\\");
        }
        if (value.contains("'")) {
            value = value.replace("'", "\\'");
        }
        if (value.contains("\"")) {
            value = value.replace("\"", "\\\"");
        }
        return "'" + value + "'";
    }
}

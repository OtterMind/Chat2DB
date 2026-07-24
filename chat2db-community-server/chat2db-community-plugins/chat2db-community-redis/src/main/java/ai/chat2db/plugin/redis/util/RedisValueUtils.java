package ai.chat2db.plugin.redis.util;

public class RedisValueUtils {

    public static String getRedisValue(String value) {
        if(value == null) {
            return null;
        }
        if(value.contains("\\")) {
            value = value.replace("\\", "\\\\");
        }
        if(value.contains("'")) {
            value = value.replace("'", "\\'");
        }
        if(value.contains("\"")) {
            value = value.replace("\"", "\\\"");
        }
        return "'"+value+"'";
    }
}

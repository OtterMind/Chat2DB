package ai.chat2db.plugin.redis.enums.type;

import ai.chat2db.plugin.redis.type.HashTypeScript;
import ai.chat2db.plugin.redis.type.ITypeScript;
import ai.chat2db.plugin.redis.type.JsonTypeScript;
import ai.chat2db.plugin.redis.type.ListTypeScript;
import ai.chat2db.plugin.redis.type.SetTypeScript;
import ai.chat2db.plugin.redis.type.StreamTypeScript;
import ai.chat2db.plugin.redis.type.StringTypeScript;
import ai.chat2db.plugin.redis.type.ZSetTypeScript;

public enum RedisDataType {
    STRING,
    LIST,
    SET,
    ZSET,
    HASH,
    STREAM,
    JSON,
    NONE;

    public ITypeScript getScript() {
        switch (this) {
            case STRING:
                return new StringTypeScript();
            case LIST:
                return new ListTypeScript();
            case SET:
                return new SetTypeScript();
            case ZSET:
                return new ZSetTypeScript();
            case HASH:
                return new HashTypeScript();
            case STREAM:
                return new StreamTypeScript();
            case JSON:
                return new JsonTypeScript();
            default:
                return new StringTypeScript();
        }
    }

    public String getCode() {
        return this.name().toLowerCase();
    }

    public static RedisDataType fromCode(String code) {
        if (code != null && (code.equalsIgnoreCase("json")
                || code.equalsIgnoreCase("ReJSON-RL")
                || code.equalsIgnoreCase("ReJSON-RS"))) {
            return JSON;
        }
        for (RedisDataType type : values()) {
            if (type.getCode().equals(code)) {
                return type;
            }
        }
        return NONE;
    }

    public static String normalizeCode(String code) {
        return fromCode(code) == JSON ? JSON.getCode() : code;
    }


}

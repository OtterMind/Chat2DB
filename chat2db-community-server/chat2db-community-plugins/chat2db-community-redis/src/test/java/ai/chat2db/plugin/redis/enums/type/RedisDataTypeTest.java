package ai.chat2db.plugin.redis.enums.type;

import ai.chat2db.plugin.redis.type.JsonTypeScript;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertEquals;

class RedisDataTypeTest {

    @Test
    void mapsRedisJsonModuleTypesToJsonScript() {
        assertEquals(RedisDataType.JSON, RedisDataType.fromCode("ReJSON-RL"));
        assertEquals("json", RedisDataType.normalizeCode("ReJSON-RS"));
        assertInstanceOf(JsonTypeScript.class, RedisDataType.fromCode("ReJSON-RL").getScript());
    }
}

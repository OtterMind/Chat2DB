package ai.chat2db.plugin.redis.type;

import ai.chat2db.plugin.redis.constant.ActionConstants;
import ai.chat2db.plugin.redis.model.RedisKey;
import ai.chat2db.plugin.redis.model.ZSetValue;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ZSetTypeScriptTest {

    private final ZSetTypeScript typeScript = new ZSetTypeScript();

    private ZSetValue member(String value, double score, String action) {
        ZSetValue zSetValue = new ZSetValue();
        zSetValue.setValue(value);
        zSetValue.setScore(score);
        zSetValue.setAction(action);
        return zSetValue;
    }

    private RedisKey key(String name, ZSetValue... values) {
        return RedisKey.builder().name(name).type("ZSET").zsValues(Arrays.asList(values)).build();
    }

    @Test
    void updateKeyRemovesOldMemberWhenValueIsEdited() {
        RedisKey oldKey = key("k", member("a", 1.0, null), member("b", 2.0, null));
        RedisKey newKey = key("k", member("a", 1.0, "original"), member("c", 3.0, ActionConstants.UPDATE));

        assertEquals(List.of("ZREM 'k' 'b' ", "ZADD 'k' 3.0 'c' "), typeScript.updateKey(oldKey, newKey));
    }

    @Test
    void updateKeyDeletesKeyWhenEveryMemberIsRemoved() {
        RedisKey oldKey = key("k", member("a", 1.0, null));
        RedisKey newKey = RedisKey.builder().name("k").type("ZSET").build();

        assertEquals(List.of("DEL 'k'\n"), typeScript.updateKey(oldKey, newKey));
    }

    @Test
    void updateKeyDeletesKeyWhenAllMembersAreFlaggedDeleted() {
        RedisKey oldKey = key("k", member("a", 1.0, null));
        RedisKey newKey = key("k", member("a", 1.0, ActionConstants.DELETE));

        assertEquals(List.of("DEL 'k'\n"), typeScript.updateKey(oldKey, newKey));
    }
}

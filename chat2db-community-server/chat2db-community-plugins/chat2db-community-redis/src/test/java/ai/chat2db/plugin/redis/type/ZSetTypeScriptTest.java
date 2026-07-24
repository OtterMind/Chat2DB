package ai.chat2db.plugin.redis.type;

import ai.chat2db.plugin.redis.constant.ActionConstants;
import ai.chat2db.plugin.redis.model.RedisKey;
import ai.chat2db.plugin.redis.model.ZSetValue;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    void updateKeyOverwritesScoreWithoutRemoval() {
        RedisKey oldKey = key("k", member("a", 1.0, null), member("b", 2.0, null));
        RedisKey newKey = key("k", member("a", 1.0, "original"), member("b", 3.0, ActionConstants.UPDATE));

        assertEquals(List.of("ZADD 'k' 3.0 'b' "), typeScript.updateKey(oldKey, newKey));
    }

    @Test
    void updateKeyRemovesOldMemberWhenValueIsEdited() {
        RedisKey oldKey = key("k", member("a", 1.0, null));
        RedisKey newKey = key("k", member("b", 1.0, ActionConstants.UPDATE));

        assertEquals(List.of("ZREM 'k' 'a' ", "ZADD 'k' 1.0 'b' "), typeScript.updateKey(oldKey, newKey));
    }

    @Test
    void updateKeyEmitsNothingWhenMembersAreUnchanged() {
        RedisKey oldKey = key("k", member("a", 1.0, null));
        RedisKey newKey = key("k", member("a", 1.0, "original"));

        assertTrue(typeScript.updateKey(oldKey, newKey).isEmpty());
    }
}

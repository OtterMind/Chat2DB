package ai.chat2db.plugin.redis.type;

import ai.chat2db.plugin.redis.constant.ActionConstants;
import ai.chat2db.plugin.redis.model.HashValue;
import ai.chat2db.plugin.redis.model.RedisKey;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HashTypeScriptTest {

    private final HashTypeScript typeScript = new HashTypeScript();

    private HashValue entry(String field, String value, String action) {
        HashValue hashValue = new HashValue();
        hashValue.setField(field);
        hashValue.setValue(value);
        hashValue.setAction(action);
        return hashValue;
    }

    private RedisKey key(String name, HashValue... values) {
        return RedisKey.builder().name(name).type("HASH").hashValues(Arrays.asList(values)).build();
    }

    @Test
    void updateKeyDeletesFieldsMissingFromDesiredState() {
        RedisKey oldKey = key("k", entry("f1", "v1", null), entry("f2", "v2", null));
        RedisKey newKey = key("k", entry("f1", "v1", "original"), entry("f3", "v3", ActionConstants.CREATE));

        assertEquals(List.of("HDEL 'k' 'f2' ", "HSET 'k' 'f3' 'v3' "), typeScript.updateKey(oldKey, newKey));
    }

    @Test
    void updateKeyOverwritesChangedFieldValue() {
        RedisKey oldKey = key("k", entry("f1", "v1", null));
        RedisKey newKey = key("k", entry("f1", "v9", ActionConstants.UPDATE));

        assertEquals(List.of("HSET 'k' 'f1' 'v9' "), typeScript.updateKey(oldKey, newKey));
    }

    @Test
    void updateKeyEmitsNothingWhenFieldsAreUnchanged() {
        RedisKey oldKey = key("k", entry("f1", "v1", null));
        RedisKey newKey = key("k", entry("f1", "v1", "original"));

        assertTrue(typeScript.updateKey(oldKey, newKey).isEmpty());
    }
}

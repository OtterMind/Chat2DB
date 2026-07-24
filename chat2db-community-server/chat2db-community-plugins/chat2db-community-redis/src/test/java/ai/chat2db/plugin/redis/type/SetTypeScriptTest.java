package ai.chat2db.plugin.redis.type;

import ai.chat2db.plugin.redis.constant.ActionConstants;
import ai.chat2db.plugin.redis.model.RedisKey;
import ai.chat2db.plugin.redis.model.SetValue;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SetTypeScriptTest {

    private final SetTypeScript typeScript = new SetTypeScript();

    private SetValue member(String value, String action) {
        SetValue setValue = new SetValue();
        setValue.setValue(value);
        setValue.setAction(action);
        return setValue;
    }

    private RedisKey key(String name, SetValue... values) {
        return RedisKey.builder().name(name).type("SET").values(Arrays.asList(values)).build();
    }

    @Test
    void updateKeyRemovesOldMemberWhenValueIsEdited() {
        RedisKey oldKey = key("k", member("a", null), member("b", null));
        RedisKey newKey = key("k", member("a", "original"), member("c", ActionConstants.UPDATE));

        List<String> scripts = typeScript.updateKey(oldKey, newKey);

        assertEquals(List.of("SREM 'k' 'b' ", "SADD 'k' 'c' "), scripts);
    }

    @Test
    void updateKeyRemovesMembersMissingFromDesiredState() {
        RedisKey oldKey = key("k", member("a", null), member("b", null));
        RedisKey newKey = key("k", member("a", "original"));

        assertEquals(List.of("SREM 'k' 'b' "), typeScript.updateKey(oldKey, newKey));
    }

    @Test
    void updateKeyEmitsNothingWhenMembersAreUnchanged() {
        RedisKey oldKey = key("k", member("a", null));
        RedisKey newKey = key("k", member("a", "original"));

        assertTrue(typeScript.updateKey(oldKey, newKey).isEmpty());
    }

    @Test
    void updateKeyOnlyAddsWhenOldMembersAreUnknown() {
        RedisKey oldKey = RedisKey.builder().name("k").type("SET").build();
        RedisKey newKey = key("k", member("a", ActionConstants.CREATE));

        assertEquals(List.of("SADD 'k' 'a' "), typeScript.updateKey(oldKey, newKey));
    }

    @Test
    void updateKeySkipsWhenDesiredStateIsEmpty() {
        RedisKey oldKey = key("k", member("a", null));
        RedisKey newKey = RedisKey.builder().name("k").type("SET").build();

        assertTrue(typeScript.updateKey(oldKey, newKey).isEmpty());
    }
}

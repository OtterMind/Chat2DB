package ai.chat2db.plugin.redis.type;

import ai.chat2db.plugin.redis.constant.ActionConstants;
import ai.chat2db.plugin.redis.model.ListValue;
import ai.chat2db.plugin.redis.model.RedisKey;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ListTypeScriptTest {

    private final ListTypeScript typeScript = new ListTypeScript();

    private ListValue item(String value, String action) {
        ListValue listValue = new ListValue();
        listValue.setValue(value);
        listValue.setAction(action);
        return listValue;
    }

    private RedisKey key(String name, ListValue... values) {
        return RedisKey.builder().name(name).type("LIST").listValues(Arrays.asList(values)).build();
    }

    @Test
    void createKeyPreservesInsertionOrder() {
        List<String> scripts = typeScript.createKey(key("k", item("v1", ActionConstants.CREATE),
                item("v2", ActionConstants.CREATE)));

        assertEquals(List.of("RPUSH 'k' 'v1' 'v2' "), scripts);
    }

    @Test
    void updateKeyRewritesKeyToDesiredState() {
        RedisKey oldKey = key("k", item("a", null), item("b", null));
        RedisKey newKey = key("k", item("a", "original"), item("edited", ActionConstants.UPDATE),
                item("b", ActionConstants.DELETE));

        List<String> scripts = typeScript.updateKey(oldKey, newKey);

        assertEquals(List.of("DEL 'k'\n", "RPUSH 'k' 'a' 'edited' "), scripts);
    }

    @Test
    void updateKeySkipsRewriteWhenNoDesiredValues() {
        RedisKey oldKey = key("k", item("a", null));
        RedisKey newKey = RedisKey.builder().name("k").type("LIST").build();

        assertTrue(typeScript.updateKey(oldKey, newKey).isEmpty());
    }

    @Test
    void updateKeyDeletesKeyWhenNewKeyIsNull() {
        assertEquals(List.of("DEL 'k'\n"), typeScript.updateKey(key("k", item("a", null)), null));
    }

    @Test
    void updateKeyCreatesKeyWhenOldKeyIsNull() {
        List<String> scripts = typeScript.updateKey(null, key("k", item("v", ActionConstants.CREATE)));

        assertEquals(List.of("RPUSH 'k' 'v' "), scripts);
    }
}

package ai.chat2db.plugin.redis.type;

import ai.chat2db.plugin.redis.constant.ActionConstants;
import ai.chat2db.plugin.redis.model.ListValue;
import ai.chat2db.plugin.redis.model.RedisKey;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

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

        // DEL + RPUSH are wrapped in MULTI/EXEC so a connection drop cannot leave the
        // key deleted without its replacement values.
        assertEquals(List.of("MULTI", "DEL 'k'\n", "RPUSH 'k' 'a' 'edited' ", "EXEC"), scripts);
    }

    @Test
    void updateKeyDeletesKeyWhenEveryElementIsRemoved() {
        RedisKey oldKey = key("k", item("a", null));
        RedisKey newKey = RedisKey.builder().name("k").type("LIST").build();

        assertEquals(List.of("DEL 'k'\n"), typeScript.updateKey(oldKey, newKey));
    }

    @Test
    void updateKeyDeletesKeyWhenAllElementsAreFlaggedDeleted() {
        RedisKey oldKey = key("k", item("a", null));
        RedisKey newKey = key("k", item("a", ActionConstants.DELETE));

        assertEquals(List.of("DEL 'k'\n"), typeScript.updateKey(oldKey, newKey));
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

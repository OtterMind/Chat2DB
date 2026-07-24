package ai.chat2db.plugin.redis.type;

import ai.chat2db.plugin.redis.model.RedisKey;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class StringTypeScriptTest {

    private final StringTypeScript typeScript = new StringTypeScript();

    private RedisKey key(String name, String value) {
        return RedisKey.builder().name(name).type("STRING").value(value).build();
    }

    @Test
    void updateKeyGeneratesSetScriptWhenOldValueIsNull() {
        List<String> scripts = typeScript.updateKey(key("k", null), key("k", "v"));

        assertEquals(List.of("SET 'k' 'v' XX \n"), scripts);
    }

    @Test
    void updateKeySkipsValueWriteWhenNewValueIsNull() {
        assertNull(typeScript.updateKey(key("k", "v"), key("renamed", null)));
    }

    @Test
    void updateKeyReturnsNullWhenValuesAreUnchanged() {
        assertNull(typeScript.updateKey(key("k", "v"), key("k", "v")));
        assertNull(typeScript.updateKey(key("k", null), key("k", null)));
    }

    @Test
    void updateKeyGeneratesSetScriptWhenValueChanges() {
        List<String> scripts = typeScript.updateKey(key("k", "old"), key("k", "new"));

        assertEquals(List.of("SET 'k' 'new' XX \n"), scripts);
    }

    @Test
    void createKeySkipsWriteWhenValueIsNull() {
        assertEquals(List.of(), typeScript.createKey(key("k", null)));
    }

    @Test
    void createKeyGeneratesSetScript() {
        assertEquals(List.of("SET 'k' 'v'"), typeScript.createKey(key("k", "v")));
    }
}

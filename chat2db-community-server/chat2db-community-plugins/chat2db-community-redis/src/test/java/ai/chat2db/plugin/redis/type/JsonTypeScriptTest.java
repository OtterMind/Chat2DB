package ai.chat2db.plugin.redis.type;

import ai.chat2db.plugin.redis.model.RedisKey;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JsonTypeScriptTest {

    private final JsonTypeScript typeScript = new JsonTypeScript();

    private RedisKey key(String name, String value) {
        return RedisKey.builder().name(name).type("json").value(value).build();
    }

    @Test
    void readsTheJsonDocumentWithJsonGet() {
        assertEquals("JSON.GET 'profile'", typeScript.getKey(key("profile", null)));
    }

    @Test
    void createsTheJsonDocumentWithJsonSet() {
        assertEquals(List.of("JSON.SET 'profile' '$' '{\"name\":\"alice\"}'"),
                typeScript.createKey(key("profile", "{\"name\":\"alice\"}")));
    }

    @Test
    void preservesTheRequestedTtlWhenCreatingTheJsonDocument() {
        RedisKey redisKey = key("profile", "{\"name\":\"alice\"}");
        redisKey.setTtl(60L);

        assertEquals(List.of("JSON.SET 'profile' '$' '{\"name\":\"alice\"}'",
                        "EXPIRE 'profile' 60"),
                typeScript.createKey(redisKey));
    }

    @Test
    void updatesTheJsonDocumentWithJsonSet() {
        assertEquals(List.of("JSON.SET 'profile' '$' '{\"name\":\"bob\"}' XX \n"),
                typeScript.updateKey(key("profile", "{\"name\":\"alice\"}"),
                        key("profile", "{\"name\":\"bob\"}")));
    }
}

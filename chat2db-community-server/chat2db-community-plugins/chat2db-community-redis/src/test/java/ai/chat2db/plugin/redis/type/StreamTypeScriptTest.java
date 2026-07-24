package ai.chat2db.plugin.redis.type;

import ai.chat2db.plugin.redis.model.RedisKey;
import ai.chat2db.plugin.redis.model.StreamValue;
import ai.chat2db.community.domain.api.model.datasource.KeyValue;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StreamTypeScriptTest {

    private final StreamTypeScript typeScript = new StreamTypeScript();

    private RedisKey key(String name, String entryId) {
        KeyValue keyValue = new KeyValue();
        keyValue.setKey("f");
        keyValue.setValue("v");
        StreamValue streamValue = new StreamValue();
        streamValue.setId(entryId);
        streamValue.setValues(List.of(keyValue));
        return RedisKey.builder().name(name).type("STREAM").streamValues(List.of(streamValue)).build();
    }

    @Test
    void createKeyDefaultsBlankEntryIdToAutoGenerate() {
        assertEquals(List.of("XADD 'k' * 'f' 'v' \n"), typeScript.createKey(key("k", "")));
    }

    @Test
    void createKeyKeepsExplicitEntryId() {
        assertEquals(List.of("XADD 'k' 1-1 'f' 'v' \n"), typeScript.createKey(key("k", "1-1")));
    }
}

package ai.chat2db.plugin.mongodb;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MongodbMetaDataTest {

    @Test
    void copyDocumentMapPreservesNullValues() {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("key", "amount");
        document.put("types", null);

        Map<String, Object> result = MongodbMetaData.copyDocumentMap(document);

        assertEquals("amount", result.get("key"));
        assertTrue(result.containsKey("types"));
        assertNull(result.get("types"));
    }
}

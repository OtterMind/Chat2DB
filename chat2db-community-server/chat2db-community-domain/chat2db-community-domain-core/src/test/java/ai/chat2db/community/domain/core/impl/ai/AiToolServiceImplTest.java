package ai.chat2db.community.domain.core.impl.ai;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiToolServiceImplTest {

    @Test
    void shouldSerializeSuccessfulToolResultAsStandardJson() {
        String json = AiToolServiceImpl.successToolResultJson(
                "SQL executed successfully with 1 result set(s).",
                List.of(Map.of("rowCount", 2)));

        JSONObject result = JSON.parseObject(json);

        assertEquals(true, result.getBoolean("success"));
        assertNull(result.get("tool"));
        assertEquals("SQL executed successfully with 1 result set(s).", result.getString("summary"));
        assertEquals(1, result.getJSONArray("data").size());
        assertNull(result.get("errorCode"));
        assertTrue(json.contains("\"errorCode\":null"));
    }

    @Test
    void shouldSerializeFailedToolResultAsStandardJson() {
        String json = AiToolServiceImpl.failureToolResultJson(
                "sql is empty.",
                "INVALID_ARGUMENT");

        JSONObject result = JSON.parseObject(json);

        assertEquals(false, result.getBoolean("success"));
        assertNull(result.get("tool"));
        assertEquals("sql is empty.", result.getString("summary"));
        assertEquals(0, result.getJSONArray("data").size());
        assertEquals("INVALID_ARGUMENT", result.getString("errorCode"));
    }
}

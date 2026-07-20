package ai.chat2db.community.domain.core.impl.ai;

import ai.chat2db.community.domain.api.model.result.Header;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
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

    @Test
    void shouldKeepRowsPositionBasedWhenColumnNamesAreDuplicated() {
        List<Header> headers = List.of(
                Header.builder().name("id").build(),
                Header.builder().name("id").build(),
                Header.builder().name("note").build());
        String longText = "a".repeat(201);

        List<List<Object>> rows = AiToolServiceImpl.rowPreviewRows(
                headers,
                List.of(Arrays.asList("first-id\nwith\ttab", null, longText)));

        assertEquals(List.of("id", "id", "note"), AiToolServiceImpl.columnNames(headers));
        assertEquals(1, rows.size());
        assertEquals("first-id\\nwith tab", rows.get(0).get(0));
        assertNull(rows.get(0).get(1));
        assertEquals(200, rows.get(0).get(2).toString().length());
        assertTrue(rows.get(0).get(2).toString().endsWith("..."));
    }
}

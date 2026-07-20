package ai.chat2db.community.web.api.mcp.adapter;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiToolMcpAdapterTest {

    @Test
    void shouldSerializeText2SqlSuccessAsStandardJson() {
        String json = AiToolMcpAdapter.toolSuccess(
                "SQL generated successfully.",
                List.of(Map.of("sql", "select * from users")));

        JSONObject result = JSON.parseObject(json);

        assertEquals(true, result.getBoolean("success"));
        assertEquals("SQL generated successfully.", result.getString("summary"));
        assertEquals("select * from users", result.getJSONArray("data").getJSONObject(0).getString("sql"));
        assertNull(result.getString("errorCode"));
        assertTrue(json.contains("\"errorCode\":null"));
    }
}

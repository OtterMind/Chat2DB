package ai.chat2db.community.web.api.model.request.db;

import ai.chat2db.community.domain.api.model.sql.SqlParameterType;
import com.alibaba.fastjson2.JSON;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Web requests are read by Jackson and desktop requests by fastjson2; both must
 * carry the parameter type by its exact enum name and never coerce another spelling.
 */
class SqlEditorExecuteRequestParameterJsonTest {

    private static final String VALID = """
            {"dataSourceId": 1, "sql": "SELECT ?", "sqlParameters": true,
             "parameters": {"id": {"type": "NUMBER", "value": "42"}},
             "positionalParameters": [{"type": "NULL", "value": null}]}
            """;

    @Test
    void bothReadersCarryTypedValues() throws Exception {
        for (SqlEditorExecuteRequest request : new SqlEditorExecuteRequest[]{
                new ObjectMapper().readValue(VALID, SqlEditorExecuteRequest.class),
                JSON.parseObject(VALID, SqlEditorExecuteRequest.class)}) {
            assertEquals(true, request.getSqlParameters());
            assertEquals(SqlParameterType.NUMBER, request.getParameters().get("id").getType());
            assertEquals("42", request.getParameters().get("id").getValue());
            assertEquals(SqlParameterType.NULL, request.getPositionalParameters().get(0).getType());
        }
    }

    @Test
    void jacksonRejectsUnknownAndMisspelledTypes() {
        ObjectMapper mapper = new ObjectMapper();

        assertThrows(Exception.class, () -> mapper.readValue(withType("BOGUS"), SqlEditorExecuteRequest.class));
        assertThrows(Exception.class, () -> mapper.readValue(withType("number"), SqlEditorExecuteRequest.class));
    }

    @Test
    void fastjsonNeverCoercesUnknownOrMisspelledTypes() {
        assertNotCoerced(withType("BOGUS"));
        assertNotCoerced(withType("number"));
    }

    private static void assertNotCoerced(String json) {
        SqlParameterType type;
        try {
            type = JSON.parseObject(json, SqlEditorExecuteRequest.class).getParameters().get("id").getType();
        } catch (RuntimeException rejected) {
            return;
        }
        // A missing type is rejected by parameter validation before anything runs.
        assertEquals(null, type, json);
    }

    private static String withType(String type) {
        return "{\"dataSourceId\": 1, \"sql\": \"SELECT :id\", \"parameters\": {\"id\": {\"type\": \"" + type
                + "\", \"value\": \"1\"}}}";
    }
}

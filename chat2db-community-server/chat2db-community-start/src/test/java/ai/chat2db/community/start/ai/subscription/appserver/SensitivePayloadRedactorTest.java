package ai.chat2db.community.start.ai.subscription.appserver;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(value = 15, unit = TimeUnit.SECONDS)
class SensitivePayloadRedactorTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void redactsTokenFieldsAndBearerText() throws Exception {
        JsonNode node = mapper.readTree("""
                {
                  "accessToken": "secret-access",
                  "nested": { "refresh_token": "secret-refresh" },
                  "message": "Authorization: Bearer abc.def.ghi"
                }
                """);
        JsonNode redacted = SensitivePayloadRedactor.redactTree(node);
        assertEquals(SensitivePayloadRedactor.REDACTED, redacted.get("accessToken").asText());
        assertEquals(SensitivePayloadRedactor.REDACTED, redacted.path("nested").get("refresh_token").asText());
        assertFalse(redacted.get("message").asText().contains("abc.def.ghi"));
        assertTrue(redacted.get("message").asText().contains(SensitivePayloadRedactor.REDACTED));
    }

    @Test
    void leavesNonSecretFieldsIntact() throws Exception {
        JsonNode node = mapper.readTree("{\"email\":\"user@example.com\",\"planType\":\"plus\"}");
        JsonNode redacted = SensitivePayloadRedactor.redactTree(node);
        assertEquals("user@example.com", redacted.get("email").asText());
        assertEquals("plus", redacted.get("planType").asText());
    }
}

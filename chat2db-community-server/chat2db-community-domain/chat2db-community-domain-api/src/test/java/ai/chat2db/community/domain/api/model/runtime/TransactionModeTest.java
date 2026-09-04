package ai.chat2db.community.domain.api.model.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TransactionModeTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void keepsTheExistingJsonValues() throws Exception {
        assertEquals("\"auto\"", objectMapper.writeValueAsString(TransactionMode.AUTO));
        assertEquals("\"manual\"", objectMapper.writeValueAsString(TransactionMode.MANUAL));
        assertEquals(TransactionMode.AUTO, objectMapper.readValue("\"auto\"", TransactionMode.class));
        assertEquals(TransactionMode.MANUAL, objectMapper.readValue("\"manual\"", TransactionMode.class));
    }
}

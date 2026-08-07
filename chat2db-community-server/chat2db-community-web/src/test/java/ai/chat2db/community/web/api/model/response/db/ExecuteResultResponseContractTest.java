package ai.chat2db.community.web.api.model.response.db;

import ai.chat2db.community.domain.api.model.result.ExecutionMetrics;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ExecuteResultResponseContractTest {

    @Test
    void exposesExecutionMetricsWithoutLegacyDuration() throws Exception {
        ExecuteResultResponse response = new ExecuteResultResponse();
        response.setExecutionMetrics(ExecutionMetrics.builder()
                .totalDurationMs(6L)
                .executeDurationMs(2L)
                .fetchDurationMs(4L)
                .fetchedRowCount(5)
                .build());

        JsonNode json = new ObjectMapper().readTree(new ObjectMapper().writeValueAsString(response));

        assertFalse(json.has("duration"));
        assertEquals(6L, json.path("executionMetrics").path("totalDurationMs").longValue());
        assertEquals(2L, json.path("executionMetrics").path("executeDurationMs").longValue());
        assertEquals(4L, json.path("executionMetrics").path("fetchDurationMs").longValue());
        assertEquals(5, json.path("executionMetrics").path("fetchedRowCount").intValue());
    }
}

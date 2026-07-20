package ai.chat2db.community.web.api.adapter.ai;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AiToolAdapterTest {

    @Test
    void shouldUseSummaryForStructuredToolTraceContent() {
        String content = """
                {
                  "success": true,
                  "summary": "SQL executed successfully.",
                  "data": [{"rows": [["very large raw payload"]]}],
                  "errorCode": null
                }
                """;

        assertEquals("SQL executed successfully.", AiToolAdapter.traceSummary(content));
    }

    @Test
    void shouldKeepRawTraceContentWhenToolResultIsNotJson() {
        assertEquals("plain result", AiToolAdapter.traceSummary("plain result"));
    }
}

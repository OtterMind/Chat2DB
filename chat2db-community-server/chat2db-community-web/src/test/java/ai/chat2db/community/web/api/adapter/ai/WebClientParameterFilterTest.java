package ai.chat2db.community.web.api.adapter.ai;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebClientParameterFilterTest {

    @Test
    void shouldNormalizeMalformedClaudeMessageStartEvent() {
        WebClientParameterFilter filter = new WebClientParameterFilter("claude");
        StringBuilder pending = new StringBuilder();

        List<String> events = filter.rewriteClaudeStreamChunk("""
                event: message_start
                data: {"message":{"content":[[]],"id":"msg_vrtx_01XvHrJMLZwea1eTCdMgutXn","model":"claude-sonnet-4-6","role":"assistant","stop_reason":null,"stop_sequence":null,"type":"message","usage":{"input_tokens":1262,"output_tokens":1}},"type":"message_start"}

                """, pending);

        assertEquals(1, events.size());
        assertTrue(events.get(0).contains("\"content\":[]"));
        assertTrue(events.get(0).contains("\"type\":\"message_start\""));
        assertEquals(0, pending.length());
    }

    @Test
    void shouldKeepPartialClaudeSseChunkUntilEventCompleted() {
        WebClientParameterFilter filter = new WebClientParameterFilter("claude");
        StringBuilder pending = new StringBuilder();

        List<String> partial = filter.rewriteClaudeStreamChunk(
                "event: message_start\n"
                        + "data: {\"message\":{\"content\":[[]],\"id\":\"msg_vrtx_01XvHrJMLZwea1eTCdMgutXn\"",
                pending);

        assertTrue(partial.isEmpty());

        List<String> completed = filter.rewriteClaudeStreamChunk(
                ",\"model\":\"claude-sonnet-4-6\",\"role\":\"assistant\",\"type\":\"message\"},\"type\":\"message_start\"}\n\n",
                pending);

        assertEquals(1, completed.size());
        assertTrue(completed.get(0).contains("\"content\":[]"));
        assertEquals(0, pending.length());
    }

    @Test
    void shouldInjectReasoningContentIntoOpenAiToolCallFollowUpRequest() {
        WebClientParameterFilter filter = new WebClientParameterFilter("openai");

        filter.rememberOpenAiReasoningForToolCalls("""
                data: {"choices":[{"delta":{"role":"assistant","reasoning_content":"Need schema first. "},"index":0}]}

                data: {"choices":[{"delta":{"tool_calls":[{"id":"call_123","type":"function","function":{"name":"get_tables_schema","arguments":"{}"}}]},"index":0}]}

                data: [DONE]

                """);

        String modifiedBody = filter.modifyRequestBody("""
                {"messages":[
                  {"role":"user","content":"query recent users"},
                  {"role":"assistant","content":"","tool_calls":[{"id":"call_123","type":"function","function":{"name":"get_tables_schema","arguments":"{}"}}]},
                  {"role":"tool","tool_call_id":"call_123","content":"schema"}
                ],"stream":true}
                """);

        assertTrue(modifiedBody.contains("\"reasoning_content\":\"Need schema first. \""));
    }

    @Test
    void shouldNotExposeSecretsInHeaderSummary() {
        WebClientParameterFilter filter = new WebClientParameterFilter("openai");
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.AUTHORIZATION, "Bearer secret-api-key-1234567890");
        headers.add(HttpHeaders.COOKIE, "CHAT2DB=session-secret-value; tenant=organization-secret-value");
        headers.add("X-Internal-Token", "internal-token-secret-value");

        String summary = filter.summarizeHeaders(headers).toString();

        assertFalse(summary.contains("secret-api-key-1234567890"));
        assertFalse(summary.contains("session-secret-value"));
        assertFalse(summary.contains("organization-secret-value"));
        assertFalse(summary.contains("internal-token-secret-value"));
        assertTrue(summary.contains("****"));
    }

    @Test
    void shouldSummarizeModelBodyWithoutPromptOrCredentials() {
        WebClientParameterFilter filter = new WebClientParameterFilter("openai");

        Map<String, Object> summary = filter.summarizeBody("""
                {"model":"gpt-test","stream":true,"apiKey":"secret-key",
                 "messages":[{"role":"user","content":"sensitive enterprise prompt"}],
                 "tools":[{"type":"function"}]}
                """);

        assertEquals("json", summary.get("format"));
        assertEquals("gpt-test", summary.get("model"));
        assertEquals(true, summary.get("stream"));
        assertEquals(1, summary.get("messageCount"));
        assertEquals(1, summary.get("toolCount"));
        assertFalse(summary.toString().contains("secret-key"));
        assertFalse(summary.toString().contains("sensitive enterprise prompt"));
    }
}

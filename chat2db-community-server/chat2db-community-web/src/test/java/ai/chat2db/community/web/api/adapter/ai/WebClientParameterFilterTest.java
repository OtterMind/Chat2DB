package ai.chat2db.community.web.api.adapter.ai;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
    void shouldPreserveClaudeThinkingInToolUseFollowUpRequest() {
        WebClientParameterFilter filter = new WebClientParameterFilter("claude");
        filter.rememberClaudeThinkingForToolCalls("""
                event: content_block_start
                data: {"type":"content_block_start","index":0,"content_block":{"type":"thinking","thinking":"","signature":"sig"}}

                event: content_block_delta
                data: {"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"Need live data."}}

                event: content_block_start
                data: {"type":"content_block_start","index":1,"content_block":{"type":"tool_use","id":"toolu_123","name":"list_all_datasources","input":{}}}

                """);

        String modifiedBody = filter.modifyRequestBody("""
                {"messages":[
                  {"role":"user","content":[{"type":"text","text":"list data sources"}]},
                  {"role":"assistant","content":[{"type":"tool_use","id":"toolu_123","name":"list_all_datasources","input":{}}]},
                  {"role":"user","content":[{"type":"tool_result","tool_use_id":"toolu_123","content":"[]"}]}
                ],"stream":true}
                """);

        assertTrue(modifiedBody.contains("\"type\":\"thinking\",\"thinking\":\"Need live data.\",\"signature\":\"sig\""));
        assertTrue(modifiedBody.indexOf("\"type\":\"thinking\"")
                < modifiedBody.indexOf("\"type\":\"tool_use\""));
    }
}

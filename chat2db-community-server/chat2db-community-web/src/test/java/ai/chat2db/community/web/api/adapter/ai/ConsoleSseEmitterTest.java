package ai.chat2db.community.web.api.adapter.ai;

import ai.chat2db.community.tools.console.ConsoleOutboundRegistry;
import ai.chat2db.community.tools.console.ConsoleResult;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

class ConsoleSseEmitterTest {

    @AfterEach
    void clearOutbound() {
        ConsoleOutboundRegistry.register(null);
    }

    @Test
    void completeSendsNullTerminal() {
        List<String> messages = new ArrayList<>();
        ConsoleOutboundRegistry.register(messages::add);
        ConsoleSseEmitter emitter = new ConsoleSseEmitter(ConsoleResult.builder().uuid("normal-request").build());

        emitter.complete();

        assertEquals(1, messages.size());
        JSONObject result = JSON.parseObject(messages.get(0));
        assertEquals("ai_sse_message", result.getString("actionType"));
        assertNull(result.get("message"));
    }

    @Test
    void completeWithErrorSendsSanitizedErrorTerminal() {
        List<String> messages = new ArrayList<>();
        ConsoleOutboundRegistry.register(messages::add);
        ConsoleSseEmitter emitter = new ConsoleSseEmitter(ConsoleResult.builder().uuid("failed-request").build());

        emitter.completeWithError(new IllegalStateException("provider token=secret"));

        assertEquals(1, messages.size());
        JSONObject result = JSON.parseObject(messages.get(0));
        JSONObject message = result.getJSONObject("message");
        assertEquals("error", message.getString("event"));

        JSONObject payload = JSON.parseObject(message.getString("data"));
        assertEquals("error", payload.getString("type"));
        assertEquals("error", payload.getString("messageType"));
        assertEquals("AI stream failed", payload.getString("content"));
        assertFalse(messages.get(0).contains("secret"));
    }
}

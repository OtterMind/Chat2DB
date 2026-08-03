package ai.chat2db.community.web.api.adapter.ai;

import ai.chat2db.community.tools.console.ConsoleOutboundRegistry;
import ai.chat2db.community.tools.console.ConsoleResult;
import com.alibaba.fastjson2.JSON;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;


@Slf4j
public class ConsoleSseEmitter extends SseEmitter {

    private static final String AI_SSE_MESSAGE = "ai_sse_message";

    private static final String AI_STREAM_ERROR_MESSAGE = "AI stream failed";

    private final ConsoleResult consoleResult;

    public ConsoleSseEmitter(ConsoleResult consoleResult) {
        super(0L);
        this.consoleResult = consoleResult;
    }


    public void sendData(String eventName, Object data) throws IOException {
        Map<String, Object> map = new HashMap<>();
        map.put("event", eventName);
        map.put("data", JSON.toJSONString(data));
        consoleResult.setMessage(map);
        consoleResult.setActionType(AI_SSE_MESSAGE);
        String json = JSON.toJSONString(consoleResult);
        ConsoleOutboundRegistry.send(json);
    }

    @Override
    public void complete() {
        try {
            log.info("ai_complete via JCEF IPC");
            consoleResult.setMessage(null);
            consoleResult.setActionType(AI_SSE_MESSAGE);
            String json = JSON.toJSONString(consoleResult);
            ConsoleOutboundRegistry.send(json);
        } finally {
            super.complete();
        }
    }

    @Override
    public void completeWithError(Throwable ex) {
        log.warn("ai stream error via JCEF IPC", ex);
        try {
            Map<String, Object> payload = AiChatTraceSupport.payload(AiChatTraceSupport.TYPE_ERROR);
            payload.put("content", AI_STREAM_ERROR_MESSAGE);
            sendData(AiChatTraceSupport.TYPE_ERROR, payload);
        } catch (Exception sendError) {
            log.warn("failed to send AI stream error via JCEF IPC", sendError);
        } finally {
            super.completeWithError(ex);
        }
    }
}

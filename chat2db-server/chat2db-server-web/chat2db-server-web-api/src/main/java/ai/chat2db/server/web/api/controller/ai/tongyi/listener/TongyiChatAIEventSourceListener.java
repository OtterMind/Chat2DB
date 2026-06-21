package ai.chat2db.server.web.api.controller.ai.tongyi.listener;

import ai.chat2db.server.web.api.controller.ai.fastchat.model.FastChatChoice;
import ai.chat2db.server.web.api.controller.ai.fastchat.model.FastChatCompletions;
import ai.chat2db.server.web.api.controller.ai.fastchat.model.FastChatCompletionsUsage;
import ai.chat2db.server.web.api.controller.ai.fastchat.model.FastChatMessage;
import ai.chat2db.server.web.api.controller.ai.tongyi.model.TongyiChatCompletions;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.unfbx.chatgpt.entity.chat.Message;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okhttp3.sse.EventSource;
import okhttp3.sse.EventSourceListener;
import org.apache.commons.lang3.StringUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * description：OpenAIEventSourceListener
 *
 * @author https:www.unfbx.com
 * @date 2023-02-22
 */
@Slf4j
public class TongyiChatAIEventSourceListener extends EventSourceListener {

    private SseEmitter sseEmitter;

    private ObjectMapper mapper = new ObjectMapper().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    public TongyiChatAIEventSourceListener(SseEmitter sseEmitter) {
        this.sseEmitter = sseEmitter;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void onOpen(EventSource eventSource, Response response) {
        log.info("Tongyi Chat Sse connecting...");
    }

    /**
     * {@inheritDoc}
     */
    @SneakyThrows
    @Override
    public void onEvent(EventSource eventSource, String id, String type, String data) {
        log.info("Tongyi Chat AI response data：{}", data);
        if (data.contains("\"finish_reason\":\"stop\"")) {
            TongyiChatCompletions chatCompletions = mapper.readValue(data, TongyiChatCompletions.class);
            String text = chatCompletions.getOutput().getText();
            log.info("id: {}, text: {}", chatCompletions.getId(), text);
            String sqlContent = "";
            if (text.indexOf("```sql") == -1) {
                sqlContent = text;
            } else {
                Pattern pattern = Pattern.compile("```sql(.*?)```", Pattern.DOTALL);
                Matcher matcher = pattern.matcher(text);
                if (matcher.find()) {
                    sqlContent = matcher.group(1).trim();
                }
            }
            Message message = new Message();
            message.setContent(sqlContent);
            sseEmitter.send(SseEmitter.event()
                    .id(null)
                    .data(message)
                    .reconnectTime(3000));
        }
    }

    @Override
    public void onClosed(EventSource eventSource) {
        try {
            sseEmitter.send(SseEmitter.event()
                .id("[DONE]")
                .data("[DONE]"));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        sseEmitter.complete();
        log.info("TongyiChatAI close sse connection...");
    }

    @Override
    public void onFailure(EventSource eventSource, Throwable t, Response response) {
        try {
            if (Objects.isNull(response)) {
                String message = t.getMessage();
                Message sseMessage = new Message();
                sseMessage.setContent(message);
                sseEmitter.send(SseEmitter.event()
                    .id("[ERROR]")
                    .data(sseMessage));
                sseEmitter.send(SseEmitter.event()
                    .id("[DONE]")
                    .data("[DONE]"));
                sseEmitter.complete();
                return;
            }
            ResponseBody body = response.body();
            String bodyString = Objects.nonNull(t) ? t.getMessage() : "";
            if (Objects.nonNull(body)) {
                bodyString = body.string();
                if (StringUtils.isBlank(bodyString) && Objects.nonNull(t)) {
                    bodyString = t.getMessage();
                }
                log.error("Tongyi Chat AI sse response：{}", bodyString);
            } else {
                log.error("Tongyi Chat AI sse response：{}，error：{}", response, t);
            }
            eventSource.cancel();
            Message message = new Message();
            message.setContent("Tongyi Chat AI error：" + bodyString);
            sseEmitter.send(SseEmitter.event()
                .id("[ERROR]")
                .data(message));
            sseEmitter.send(SseEmitter.event()
                .id("[DONE]")
                .data("[DONE]"));
            sseEmitter.complete();
        } catch (Exception exception) {
            log.error("Tongyi Chat AI send data error:", exception);
        }
    }
}

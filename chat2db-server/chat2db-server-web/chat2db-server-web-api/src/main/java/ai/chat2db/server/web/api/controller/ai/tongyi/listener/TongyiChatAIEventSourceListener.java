package ai.chat2db.server.web.api.controller.ai.tongyi.listener;

import ai.chat2db.server.web.api.controller.ai.tongyi.model.TongyiChatChoice;
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
import java.util.List;
import java.util.Objects;

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
        if (data.equals("[DONE]")) {
            log.info("Tongyi Chat AI closed");
            sseEmitter.send(SseEmitter.event()
                .id("[DONE]")
                .data("[DONE]")
                .reconnectTime(3000));
            sseEmitter.complete();
            return;
        }

        TongyiChatCompletions chatCompletions = mapper.readValue(data, TongyiChatCompletions.class);
        List<TongyiChatChoice> choices = chatCompletions.getChoices();
        for (TongyiChatChoice choice : choices) {
            String text = choice.getDelta().getContent();
            log.info("id: {}, text: {}", chatCompletions.getId(), text);

            Message message = new Message();
            message.setContent(text);
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

package ai.chat2db.server.web.api.controller.ai.statemachine.actions;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.statemachine.StateContext;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.alibaba.fastjson2.JSONObject;

import ai.chat2db.server.web.api.controller.ai.enums.PromptType;
import ai.chat2db.server.web.api.controller.ai.service.AiConversationCache;
import ai.chat2db.server.web.api.controller.ai.prompt.PromptValidator;
import ai.chat2db.server.web.api.controller.ai.statemachine.ChatContext;
import ai.chat2db.server.web.api.controller.ai.statemachine.ChatEvent;
import ai.chat2db.server.web.api.controller.ai.statemachine.ChatState;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * AI 流式输出动作
 */
@Component
@Slf4j
public class StreamAction extends BaseChatAction {

    @Autowired
    private PromptValidator promptValidator;

    @Autowired
    private AiConversationCache aiConversationCache;

    @Override
    public void execute(StateContext<ChatState, ChatEvent> context) {
        ChatContext ctx = getChatContext(context);
        if (ctx.isCancelled()) {
            log.info("[StreamAction] Skip cancelled request, uid: {}", ctx.getUid());
            return;
        }

        String prompt = ctx.getBuiltPrompt();

        if (!promptValidator.isValidLength(prompt)) {
            log.warn("[StreamAction] Prompt exceeds max length, uid: {}, promptLength: {}",
                    ctx.getUid(), prompt == null ? 0 : prompt.length());
            sendError(ctx.getSseEmitter(), "提示语超出最大长度");
            context.getStateMachine().sendEvent(
                    Mono.just(MessageBuilder.withPayload(ChatEvent.AI_CALL_FAILED).build())
            ).subscribe();
            return;
        }

        try {
            sendStateEvent(ctx.getSseEmitter(), ChatState.STREAMING, "AI 正在生成...");
            log.info("[StreamAction] Starting AI stream, uid: {}, promptLength: {}",
                    ctx.getUid(), prompt == null ? 0 : prompt.length());

            Flux<ChatResponse> flux = ctx.getChatClient().prompt()
                    .user(prompt)
                    .stream()
                    .chatResponse();
            flux.publishOn(Schedulers.boundedElastic())
                    .doOnNext(chatResponse -> processChatResponse(ctx, chatResponse))
                    .doOnError(error -> handleStreamError(ctx, error, context))
                    .doOnComplete(() -> handleStreamComplete(ctx, context))
                    .subscribe();

        } catch (Exception e) {
            log.error("[StreamAction] Start streaming failed for uid: {}", ctx.getUid(), e);
            sendError(ctx.getSseEmitter(), "启动 AI 流式调用失败：" + e.getMessage());
            context.getStateMachine().sendEvent(
                    Mono.just(MessageBuilder.withPayload(ChatEvent.AI_CALL_FAILED).build())
            ).subscribe();
        }
    }

    /**
     * 处理每个聊天响应
     */
    private void processChatResponse(ChatContext ctx, ChatResponse chatResponse) {
        if (ctx.isCancelled()) {
            log.info("[StreamAction] Stream cancelled by user for uid: {}", ctx.getUid());
            throw new RuntimeException("Cancelled by user");
        }

        JSONObject data = new JSONObject();
        Generation generation = chatResponse.getResult();

        String content = generation.getOutput().getText();
        String thinking = extractThinkingContent(generation);

        if (content != null && !content.isEmpty()) {
            data.put("content", content);
            appendContent(ctx, content);
        }
        if (thinking != null && !thinking.isEmpty()) {
            data.put("thinking", thinking);
            appendThinking(ctx, thinking);
            log.debug("[StreamAction] Thinking content: {}", thinking);
        }

        if (!data.isEmpty()) {
            try {
                ctx.getSseEmitter().send(SseEmitter.event()
                        .id(ctx.getUid())
                        .name("message")
                        .data(data.toJSONString())
                        .reconnectTime(3000));
            } catch (IOException e) {
                log.error("[StreamAction] Failed to send SSE message for uid: {}", ctx.getUid(), e);
                throw new RuntimeException(e);
            }
        }
    }

    private synchronized void appendContent(ChatContext ctx, String content) {
        String current = ctx.getCurrentContent();
        ctx.setCurrentContent(current == null ? content : current + content);
    }

    private synchronized void appendThinking(ChatContext ctx, String thinking) {
        String current = ctx.getCurrentThinking();
        ctx.setCurrentThinking(current == null ? thinking : current + thinking);
    }

    /**
     * 从生成结果中提取思考内容
     */
    private String extractThinkingContent(Generation generation) {
        ChatGenerationMetadata metadata = generation.getMetadata();
        if (metadata.containsKey("thinking")) {
            Object thinkingObj = metadata.get("thinking");
            if (thinkingObj instanceof String) {
                return (String) thinkingObj;
            } else if (thinkingObj != null) {
                return thinkingObj.toString();
            }
        } else {
            Map<String, Object> outputMetadata = generation.getOutput().getMetadata();
            if (outputMetadata.containsKey("reasoningContent")) {
                return outputMetadata.get("reasoningContent").toString();
            } else if (outputMetadata.containsKey("reasoning_details")) {
                return outputMetadata.get("reasoning_details").toString();
            }
        }
        return null;
    }

    /**
     * 处理流式错误
     */
    private void handleStreamError(ChatContext ctx, Throwable error, StateContext<ChatState, ChatEvent> context) {
        log.error("[StreamAction] Stream error for uid: {}", ctx.getUid(), error);
        if (!ctx.isCancelled()) {
            sendError(ctx.getSseEmitter(), "AI 调用失败：" + error.getMessage());
        }
        try {
            ctx.getSseEmitter().completeWithError(error);
        } catch (Exception ignored) {
            // 忽略完成时的异常
        }
        context.getStateMachine().sendEvent(
                MessageBuilder.withPayload(ChatEvent.AI_CALL_FAILED).build()
        );
    }

    /**
     * 处理流式完成
     */
    private void handleStreamComplete(ChatContext ctx, StateContext<ChatState, ChatEvent> context) {
        log.info("[StreamAction] Stream completed for uid: {}", ctx.getUid());
        try {
            cacheConversationTurn(ctx);
            ctx.getSseEmitter().send(SseEmitter.event()
                    .id("[DONE]")
                    .data("[DONE]")
                    .reconnectTime(3000));
        } catch (IOException ignored) {
            // 忽略发送完成消息时的异常
        } finally {
            ctx.getSseEmitter().complete();
        }
        context.getStateMachine().sendEvent(
                MessageBuilder.withPayload(ChatEvent.STREAM_FINISHED).build()
        );
    }

    private void cacheConversationTurn(ChatContext ctx) {
        if (ctx.getRequest() == null
                || !PromptType.NL_2_SQL.getCode().equals(ctx.getRequest().getPromptType())) {
            return;
        }
        aiConversationCache.appendTurn(
                ctx.getRequest().getConversationId(),
                ctx.getRequest().getMessage(),
                ctx.getCurrentContent());
    }
}

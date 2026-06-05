package ai.chat2db.server.web.api.controller.ai;

import java.io.IOException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.commons.collections4.CollectionUtils;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.statemachine.StateMachine;
import org.springframework.statemachine.config.StateMachineFactory;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import ai.chat2db.server.domain.api.model.AiConversation;
import ai.chat2db.server.domain.api.param.ai.AiConversationCreateParam;
import ai.chat2db.server.domain.api.service.AiConversationService;
import ai.chat2db.server.domain.repository.Dbutils;
import ai.chat2db.server.tools.common.exception.ParamBusinessException;
import ai.chat2db.server.tools.common.model.Context;
import ai.chat2db.server.tools.common.model.LoginUser;
import ai.chat2db.server.tools.common.util.ContextUtils;
import ai.chat2db.server.web.api.aspect.ConnectionInfoAspect;
import ai.chat2db.server.web.api.config.AiChatConfig;
import ai.chat2db.server.web.api.controller.ai.enums.PromptType;
import ai.chat2db.server.web.api.controller.ai.request.ChatQueryRequest;
import ai.chat2db.server.web.api.controller.ai.statemachine.ChatContext;
import ai.chat2db.server.web.api.controller.ai.statemachine.ChatEvent;
import ai.chat2db.server.web.api.controller.ai.statemachine.ChatState;
import ai.chat2db.server.web.api.controller.ai.task.AiConversationTitleTask;
import ai.chat2db.spi.sql.Chat2DBContext;
import ai.chat2db.spi.sql.ConnectInfo;
import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

@RestController
@ConnectionInfoAspect
@RequestMapping("/api/ai")
@Slf4j
public class ChatController {

    @Autowired
    private StateMachineFactory<ChatState, ChatEvent> stateMachineFactory;

    @Autowired
    private AiChatConfig aiChatConfig;

    @Autowired
    private AiConversationService aiConversationService;

    @Autowired
    private AiConversationTitleTask aiConversationTitleTask;

    private static final Long CHAT_TIMEOUT = Duration.ofMinutes(50).toMillis();

    private final ConcurrentHashMap<String, StateMachine<ChatState, ChatEvent>> activeSessions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ChatContext> activeContexts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, PendingChatPayload> pendingPayloads = new ConcurrentHashMap<>();

    private static final long PAYLOAD_TIMEOUT = Duration.ofMinutes(5).toMillis();

    @PostMapping("/chat/payload")
    @CrossOrigin
    public ResponseEntity<Map<String, String>> createChatPayload(@RequestBody ChatQueryRequest queryRequest) {
        cleanupExpiredPayloads();

        String payloadId = UUID.randomUUID().toString();
        pendingPayloads.put(payloadId, new PendingChatPayload(queryRequest, System.currentTimeMillis()));
        return ResponseEntity.ok(Map.of("payloadId", payloadId));
    }

    @GetMapping("/chat")
    @CrossOrigin
    public SseEmitter chat(ChatQueryRequest queryRequest, @RequestHeader Map<String, String> headers)
            throws IOException {
        String uid = headers.get("uid");
        if (StrUtil.isBlank(uid)) {
            throw new ParamBusinessException("uid");
        }

        log.info("[ChatController] Received uid from client: {}", uid);
        queryRequest = resolvePayload(queryRequest);
        SseEmitter sseEmitter = new SseEmitter(CHAT_TIMEOUT);

        // 根据提示类型选择是否使用快速模型
        PromptType promptType = null;
        if (StrUtil.isNotBlank(queryRequest.getPromptType())) {
            try {
                promptType = PromptType.valueOf(queryRequest.getPromptType());
            } catch (Exception ignored) {
                log.warn("[ChatController] Invalid promptType: {}", queryRequest.getPromptType());
            }
        }

        ChatClient chatClient = aiChatConfig.createChatClient(promptType);

        LoginUser loginUser = ContextUtils.getLoginUser();
        ConnectInfo connectInfo = Chat2DBContext.getConnectInfo().copy();

        ensureConversationExists(queryRequest, loginUser);
        boolean firstTurn = isFirstTurnOfConversation(queryRequest, loginUser);

        ChatContext ctx = ChatContext.builder()
                .uid(uid)
                .request(queryRequest)
                .sseEmitter(sseEmitter)
                .chatClient(chatClient)
                .cancelled(false)
                .loginUser(loginUser)
                .connectInfo(connectInfo)
                .userMessageId(queryRequest.getUserMessageId())
                .firstTurnOfConversation(firstTurn)
                .build();

        setupSseCallbacks(sseEmitter, uid, ctx);

        sseEmitter.send(SseEmitter.event()
                .id(uid)
                .name("connect")
                .data(LocalDateTime.now().toString())
                .reconnectTime(3000));

        StateMachine<ChatState, ChatEvent> stateMachine = stateMachineFactory.getStateMachine(uid);
        stateMachine.getExtendedState().getVariables().put("chatContext", ctx);

        activeSessions.put(uid, stateMachine);
        activeContexts.put(uid, ctx);
        log.info("[ChatController] Session stored with uid: {}, activeSessions size: {}, activeContexts size: {}",
                uid, activeSessions.size(), activeContexts.size());

        stateMachine.startReactively().subscribe();
        ChatEvent initialEvent = determineInitialEvent(queryRequest);
        stateMachine.sendEvent(
                Mono.just(MessageBuilder.withPayload(initialEvent).build())
        ).subscribe();

        if (firstTurn && promptType == PromptType.NL_2_SQL) {
            try {
                aiConversationTitleTask.generateTitleAsync(queryRequest.getConversationId(), queryRequest.getMessage());
            } catch (Exception e) {
                log.warn("[ChatController] Failed to dispatch title task for {}: {}", queryRequest.getConversationId(), e.getMessage());
            }
        }

        return sseEmitter;
    }

    private boolean isFirstTurnOfConversation(ChatQueryRequest request, LoginUser loginUser) {
        if (request == null || StrUtil.isBlank(request.getConversationId())) {
            return false;
        }
        try {
            AiConversation conversation = aiConversationService.findByConversationId(request.getConversationId());
            return conversation == null || conversation.getMessageCount() == null || conversation.getMessageCount() == 0;
        } catch (Exception e) {
            log.warn("[ChatController] Check first turn failed for {}: {}",
                    request.getConversationId(), e.getMessage());
            return false;
        }
    }

    private void ensureConversationExists(ChatQueryRequest request, LoginUser loginUser) {
        if (request == null || StrUtil.isBlank(request.getConversationId())) {
            return;
        }
        try {
            if (aiConversationService.findByConversationId(request.getConversationId()) != null) {
                return;
            }
            AiConversationCreateParam param = new AiConversationCreateParam();
            param.setConversationId(request.getConversationId());
            param.setUserId(loginUser == null ? null : loginUser.getId());
            param.setDataSourceId(request.getDataSourceId());
            param.setDatabaseName(request.getDatabaseName());
            param.setSchemaName(request.getSchemaName());
            param.setTitle("新对话");
            aiConversationService.create(param);
        } catch (Exception e) {
            log.warn("[ChatController] Ensure conversation failed for {}: {}",
                    request.getConversationId(), e.getMessage());
        }
    }

    @DeleteMapping("/chat/{uid}")
    @CrossOrigin
    public ResponseEntity<Void> cancelChat(@PathVariable String uid) {
        log.info("[ChatController] Cancel request received for uid: {}", uid);
        log.info("[ChatController] activeSessions keys: {}", activeSessions.keySet());
        log.info("[ChatController] activeContexts keys: {}", activeContexts.keySet());
        ChatContext ctx = activeContexts.get(uid);
        if (ctx != null) {
            log.info("[ChatController] Found context for uid: {}, cancelling...", uid);
            ctx.setCancelled(true);
            try {
                ctx.getSseEmitter().complete();
            } catch (Exception ignored) {
            }
        } else {
            log.warn("[ChatController] No context found for uid: {}", uid);
        }

        StateMachine<ChatState, ChatEvent> sm = activeSessions.get(uid);
        if (sm != null) {
            log.info("[ChatController] Found state machine for uid: {}, sending CANCEL event", uid);
            sm.sendEvent(MessageBuilder.withPayload(ChatEvent.CANCEL).build());
        } else {
            log.warn("[ChatController] No state machine found for uid: {}", uid);
        }

        cleanupSession(uid);
        return ResponseEntity.ok().build();
    }

    private ChatEvent determineInitialEvent(ChatQueryRequest request) {
        boolean hasTables = CollectionUtils.isNotEmpty(request.getTableNames());

        String promptType = request.getPromptType();

        if (PromptType.TEXT_GENERATION.getCode().equals(promptType)
                || PromptType.TITLE_GENERATION.getCode().equals(promptType)) {
            return ChatEvent.TABLES_NOT_NEEDED;
        }

        if (!hasTables && PromptType.SQL_OPTIMIZER.getCode().equals(promptType)) {
            return ChatEvent.EXPLAIN_TABLES_NOT_SELECTED;
        }

        return hasTables ? ChatEvent.TABLES_PROVIDED : ChatEvent.TABLES_NOT_PROVIDED;
    }

    private void setupSseCallbacks(SseEmitter sseEmitter, String uid, ChatContext ctx) {
        sseEmitter.onCompletion(() -> {
            log.info("SSE completed for uid: {}", uid);
            cleanupSession(uid);
        });

        sseEmitter.onTimeout(() -> {
            log.info("SSE timeout for uid: {}", uid);
            ctx.setCancelled(true);
            cleanupSession(uid);
        });

        sseEmitter.onError(throwable -> {
            log.error("SSE error for uid: {}, error: {}", uid, throwable.getMessage());
            ctx.setCancelled(true);
            cleanupSession(uid);
        });
    }

    private void cleanupSession(String uid) {
        activeSessions.remove(uid);
        activeContexts.remove(uid);
    }

    private ChatQueryRequest resolvePayload(ChatQueryRequest queryRequest) {
        if (StrUtil.isBlank(queryRequest.getPayloadId())) {
            return queryRequest;
        }

        PendingChatPayload pendingPayload = pendingPayloads.remove(queryRequest.getPayloadId());
        if (pendingPayload == null) {
            throw new ParamBusinessException("payloadId");
        }

        return pendingPayload.queryRequest();
    }

    private void cleanupExpiredPayloads() {
        long now = System.currentTimeMillis();
        pendingPayloads.entrySet().removeIf(entry -> now - entry.getValue().createdAt() > PAYLOAD_TIMEOUT);
    }

    private record PendingChatPayload(ChatQueryRequest queryRequest, long createdAt) {
    }
}

package ai.chat2db.community.domain.core.impl.agent;

import ai.chat2db.community.domain.api.enums.agent.AgentDeliveryStatusEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentGatewayPlatformEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentRunStatusEnum;
import ai.chat2db.community.domain.api.model.agent.AgentDeliveryCommand;
import ai.chat2db.community.domain.api.model.agent.AgentExternalConversationBinding;
import ai.chat2db.community.domain.api.model.agent.AgentGatewayChannel;
import ai.chat2db.community.domain.api.model.agent.AgentGatewayChannelCredential;
import ai.chat2db.community.domain.api.model.agent.AgentInboundMessage;
import ai.chat2db.community.domain.api.model.agent.AgentInboundMessageResult;
import ai.chat2db.community.domain.api.model.agent.AgentRun;
import ai.chat2db.community.domain.api.model.ai.AiChatMessage;
import ai.chat2db.community.domain.api.model.ai.AiChatSession;
import ai.chat2db.community.domain.api.model.request.agent.AgentDefinitionCreateRequest;
import ai.chat2db.community.domain.api.model.request.agent.AgentGatewayChannelCreateRequest;
import ai.chat2db.community.domain.api.model.request.agent.AgentGatewayDeliveryReceiptRequest;
import ai.chat2db.community.domain.api.model.request.agent.AgentGatewayInboundRequest;
import ai.chat2db.community.domain.api.model.request.ai.AiChatMessageAddRequest;
import ai.chat2db.community.domain.api.service.agent.IAgentRunCoordinator;
import ai.chat2db.community.domain.api.service.ai.IAiChatHistoryService;
import ai.chat2db.community.domain.api.service.storage.IAgentGatewayStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.ConcurrentModificationException;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentGatewayBridgeServiceTest {

    private AgentControlServiceTest.MemoryAgentControlStorage agentStorage;
    private MemoryGatewayStorage gatewayStorage;
    private TestHistoryService history;
    private AgentGatewayBridgeServiceImpl service;
    private String agentId;

    @BeforeEach
    void setUp() {
        agentStorage = new AgentControlServiceTest.MemoryAgentControlStorage();
        gatewayStorage = new MemoryGatewayStorage();
        history = new TestHistoryService();
        AgentDefinitionServiceImpl agentService = new AgentDefinitionServiceImpl(agentStorage);
        AgentTaskServiceImpl taskService = new AgentTaskServiceImpl(agentStorage);
        AgentTaskContextServiceImpl contextService = new AgentTaskContextServiceImpl(agentStorage, taskService);
        IAgentRunCoordinator coordinator = new CompletedCoordinator();
        AgentChatTaskServiceImpl chatTaskService = new AgentChatTaskServiceImpl(
                history, agentService, taskService, contextService, coordinator);
        service = new AgentGatewayBridgeServiceImpl(gatewayStorage, agentService, chatTaskService,
                taskService, history, Clock.fixed(Instant.parse("2026-08-15T00:00:00Z"), ZoneOffset.UTC),
                () -> "gateway-token");

        AgentDefinitionCreateRequest definition = new AgentDefinitionCreateRequest();
        definition.setName("AnalysisAgent"); definition.setCreatedBy(7L);
        agentId = agentService.create(definition).getId();
    }

    @Test
    void feishuInboundCreatesOneConversationTaskAndFinalDelivery() {
        AgentGatewayChannelCredential credential = service.createChannel(channelRequest());
        AgentGatewayInboundRequest inbound = inbound("event-1", "message-1", "thread-1");

        AgentInboundMessageResult first = service.acceptInbound(
                credential.getChannel().getId(), credential.getGatewayToken(), inbound);
        AgentInboundMessageResult duplicate = service.acceptInbound(
                credential.getChannel().getId(), credential.getGatewayToken(), inbound);

        assertFalse(first.getDuplicate());
        assertTrue(duplicate.getDuplicate());
        assertEquals(first.getInboundMessage().getTaskId(), duplicate.getInboundMessage().getTaskId());
        assertEquals("ACTIVE", first.getConversationLinkStatus());
        assertEquals("ACTIVE", first.getTaskLinkStatus());
        assertEquals(1, agentStorage.listTasks().size());
        assertEquals(1, history.sessions.size());

        List<AgentDeliveryCommand> claimed = service.claimDeliveries(
                credential.getChannel().getId(), credential.getGatewayToken(), 10);
        assertEquals(1, claimed.size());
        assertEquals("thread-1", claimed.get(0).getThreadId());
        assertEquals("message-1", claimed.get(0).getReplyToMessageId());
        assertEquals("analysis complete", claimed.get(0).getContent());
        assertEquals(List.of(), service.claimDeliveries(
                credential.getChannel().getId(), credential.getGatewayToken(), 10));

        AgentGatewayDeliveryReceiptRequest receipt = new AgentGatewayDeliveryReceiptRequest();
        receipt.setExpectedRevision(claimed.get(0).getRevision());
        receipt.setDelivered(true); receipt.setPlatformMessageId("feishu-reply-1");
        AgentDeliveryCommand delivered = service.acknowledgeDelivery(
                credential.getChannel().getId(), credential.getGatewayToken(), claimed.get(0).getId(), receipt);
        assertEquals(AgentDeliveryStatusEnum.DELIVERED, delivered.getStatus());
        assertEquals("feishu-reply-1", delivered.getPlatformMessageId());
        assertEquals(delivered.getId(), service.acknowledgeDelivery(
                credential.getChannel().getId(), credential.getGatewayToken(), delivered.getId(), receipt).getId());
    }

    @Test
    void separatesThreadsAndRejectsCredentialOrIdempotencyReuse() {
        AgentGatewayChannelCredential credential = service.createChannel(channelRequest());
        service.acceptInbound(credential.getChannel().getId(), credential.getGatewayToken(),
                inbound("event-1", "message-1", "thread-1"));
        service.acceptInbound(credential.getChannel().getId(), credential.getGatewayToken(),
                inbound("event-2", "message-2", "thread-2"));
        assertEquals(2, history.sessions.size());

        AgentGatewayInboundRequest conflict = inbound("event-1", "message-1", "thread-1");
        conflict.setText("different content");
        assertThrows(IllegalStateException.class, () -> service.acceptInbound(
                credential.getChannel().getId(), credential.getGatewayToken(), conflict));
        assertThrows(SecurityException.class, () -> service.acceptInbound(
                credential.getChannel().getId(), "wrong-token", inbound("event-3", "message-3", "thread-1")));
    }

    @Test
    void reportsArchivedAndDeletedLinksWithoutFollowingThem() {
        AgentGatewayChannelCredential credential = service.createChannel(channelRequest());
        AgentGatewayInboundRequest request = inbound("event-1", "message-1", "");
        AgentInboundMessageResult first = service.acceptInbound(
                credential.getChannel().getId(), credential.getGatewayToken(), request);
        String taskId = first.getInboundMessage().getTaskId();
        var task = agentStorage.getTask(taskId);
        task.setArchivedAt(new Date());
        assertEquals("ARCHIVED", service.acceptInbound(
                credential.getChannel().getId(), credential.getGatewayToken(), request).getTaskLinkStatus());
        agentStorage.deleteTask(taskId, task.getRevision());
        history.deleteSession(first.getBinding().getSessionId(), 7L);
        AgentInboundMessageResult deleted = service.acceptInbound(
                credential.getChannel().getId(), credential.getGatewayToken(), request);
        assertEquals("DELETED", deleted.getTaskLinkStatus());
        assertEquals("DELETED", deleted.getConversationLinkStatus());
    }

    @Test
    void failedDeliveryMovesToDeadLetterAtBoundedAttemptLimit() {
        AgentGatewayChannelCredential credential = service.createChannel(channelRequest());
        service.acceptInbound(credential.getChannel().getId(), credential.getGatewayToken(),
                inbound("event-1", "message-1", "thread-1"));
        AgentDeliveryCommand claimed = service.claimDeliveries(
                credential.getChannel().getId(), credential.getGatewayToken(), 1).get(0);
        claimed.setAttemptCount(AgentGatewayBridgeServiceImpl.MAX_DELIVERY_ATTEMPTS);
        AgentGatewayDeliveryReceiptRequest receipt = new AgentGatewayDeliveryReceiptRequest();
        receipt.setExpectedRevision(claimed.getRevision()); receipt.setDelivered(false);
        receipt.setError("Feishu rate limit persisted");

        AgentDeliveryCommand failed = service.acknowledgeDelivery(
                credential.getChannel().getId(), credential.getGatewayToken(), claimed.getId(), receipt);

        assertEquals(AgentDeliveryStatusEnum.DEAD_LETTER, failed.getStatus());
        assertEquals("Feishu rate limit persisted", failed.getLastError());
    }

    @Test
    void dingtalkUsesTheSameTransportBridgeWithoutStartingASecondRuntime() {
        AgentGatewayChannelCreateRequest channel = channelRequest();
        channel.setName("DingTalk bridge");
        channel.setPlatform(AgentGatewayPlatformEnum.DINGTALK);
        channel.setInstallationRef("dingtalk-local-profile");
        AgentGatewayChannelCredential credential = service.createChannel(channel);

        AgentInboundMessageResult accepted = service.acceptInbound(
                credential.getChannel().getId(), credential.getGatewayToken(),
                inbound("dingtalk-event-1", "dingtalk-message-1", ""));

        assertNotNull(accepted.getInboundMessage().getTaskId());
        assertEquals(1, agentStorage.listTasks().size());
        assertEquals(AgentGatewayPlatformEnum.DINGTALK, service.claimDeliveries(
                credential.getChannel().getId(), credential.getGatewayToken(), 1).get(0).getPlatform());
    }

    private AgentGatewayChannelCreateRequest channelRequest() {
        AgentGatewayChannelCreateRequest request = new AgentGatewayChannelCreateRequest();
        request.setName("Feishu bridge"); request.setPlatform(AgentGatewayPlatformEnum.FEISHU);
        request.setInstallationRef("feishu-local-profile"); request.setDefaultAgentId(agentId);
        request.setCreatedBy(7L);
        return request;
    }

    private AgentGatewayInboundRequest inbound(String eventId, String messageId, String threadId) {
        AgentGatewayInboundRequest request = new AgentGatewayInboundRequest();
        request.setChatId("chat-1"); request.setThreadId(threadId); request.setEventId(eventId);
        request.setMessageId(messageId); request.setIdempotencyKey(eventId); request.setSenderId("sender-1");
        request.setSenderDisplayName("Ryan"); request.setText("@AnalysisAgent analyze revenue");
        request.setMentions(List.of("AnalysisAgent")); request.setReceivedAt(new Date());
        return request;
    }

    private class CompletedCoordinator implements IAgentRunCoordinator {
        @Override
        public AgentRun dispatch(String runId) {
            AgentRun run = agentStorage.getRun(runId);
            run.setStatus(AgentRunStatusEnum.COMPLETED);
            run.setResultSummary("analysis complete");
            return run;
        }
        @Override public AgentRun resumeAfterApproval(String runId, String approvalContext) { throw new UnsupportedOperationException(); }
        @Override public AgentRun cancel(String runId) { throw new UnsupportedOperationException(); }
        @Override public List<ai.chat2db.community.domain.api.model.agent.AgentRunEvent> listEvents(String runId) { return List.of(); }
    }

    private static final class TestHistoryService implements IAiChatHistoryService {
        private final List<AiChatSession> sessions = new ArrayList<>();
        private final List<AiChatMessage> messages = new ArrayList<>();
        @Override public AiChatSession createSession(Long userId, String firstMessage) {
            AiChatSession session = new AiChatSession(); session.setId(UUID.randomUUID().toString());
            session.setUserId(userId); session.setTitle(firstMessage); session.setGmtCreate(LocalDateTime.now());
            session.setGmtModified(LocalDateTime.now()); sessions.add(session); return session;
        }
        @Override public AiChatMessage addMessage(AiChatMessageAddRequest request) {
            AiChatMessage existing = messages.stream().filter(value -> Objects.equals(value.getId(), request.getId()))
                    .findFirst().orElse(null);
            if (existing != null) return existing;
            AiChatMessage message = new AiChatMessage(); message.setId(request.getId());
            message.setSessionId(request.getSessionId()); message.setRole(request.getRole());
            message.setContent(request.getContent()); message.setMessageType(request.getMessageType());
            message.setTaskId(request.getTaskId()); message.setAgentId(request.getAgentId());
            message.setAgentName(request.getAgentName()); messages.add(message); return message;
        }
        @Override public List<AiChatSession> listSessions(Long userId) {
            return sessions.stream().filter(value -> Objects.equals(value.getUserId(), userId)).toList();
        }
        @Override public List<AiChatMessage> getMessages(String sessionId, Long userId) {
            return messages.stream().filter(value -> sessionId.equals(value.getSessionId())).toList();
        }
        @Override public List<AiChatMessage> getHistoryForAI(String sessionId, Long userId) { return getMessages(sessionId, userId); }
        @Override public void deleteSession(String sessionId, Long userId) {
            sessions.removeIf(value -> sessionId.equals(value.getId()) && Objects.equals(userId, value.getUserId()));
            messages.removeIf(value -> sessionId.equals(value.getSessionId()));
        }
    }

    private static final class MemoryGatewayStorage implements IAgentGatewayStorage {
        private final Map<String, AgentGatewayChannel> channels = new LinkedHashMap<>();
        private final Map<String, String> tokenHashes = new LinkedHashMap<>();
        private final Map<String, AgentExternalConversationBinding> bindings = new LinkedHashMap<>();
        private final Map<String, AgentInboundMessage> inbound = new LinkedHashMap<>();
        private final Map<String, AgentDeliveryCommand> deliveries = new LinkedHashMap<>();
        @Override public AgentGatewayChannel createGatewayChannel(AgentGatewayChannel channel, String tokenHash) {
            channels.put(channel.getId(), channel); tokenHashes.put(channel.getId(), tokenHash); return channel;
        }
        @Override public AgentGatewayChannel getGatewayChannel(String channelId) { return channels.get(channelId); }
        @Override public List<AgentGatewayChannel> listGatewayChannels(Long ownerId) {
            return channels.values().stream().filter(value -> Objects.equals(value.getCreatedBy(), ownerId)).toList();
        }
        @Override public boolean matchesGatewayToken(String channelId, String tokenHash) {
            return Objects.equals(tokenHashes.get(channelId), tokenHash);
        }
        @Override public AgentExternalConversationBinding getConversationBinding(String bindingId) {
            return bindings.get(bindingId);
        }
        @Override public AgentExternalConversationBinding getConversationBinding(String channelId, String chatId, String threadId) {
            return bindings.values().stream().filter(value -> value.getChannelId().equals(channelId)
                    && value.getChatId().equals(chatId) && value.getThreadId().equals(threadId)).findFirst().orElse(null);
        }
        @Override public AgentExternalConversationBinding createConversationBinding(AgentExternalConversationBinding binding) {
            AgentExternalConversationBinding existing = getConversationBinding(
                    binding.getChannelId(), binding.getChatId(), binding.getThreadId());
            if (existing != null) return existing; bindings.put(binding.getId(), binding); return binding;
        }
        @Override public AgentInboundMessage getInboundMessage(String channelId, String idempotencyKey) {
            return inbound.values().stream().filter(value -> value.getChannelId().equals(channelId)
                    && value.getIdempotencyKey().equals(idempotencyKey)).findFirst().orElse(null);
        }
        @Override public AgentInboundMessage createInboundMessage(AgentInboundMessage message) {
            AgentInboundMessage existing = getInboundMessage(message.getChannelId(), message.getIdempotencyKey());
            if (existing != null) return existing; inbound.put(message.getId(), message); return message;
        }
        @Override public AgentInboundMessage attachInboundTask(String messageId, String taskId, long expectedRevision) {
            AgentInboundMessage message = inbound.get(messageId);
            if (message == null || message.getRevision() != expectedRevision) throw new ConcurrentModificationException();
            message.setTaskId(taskId); message.setRevision(expectedRevision + 1); return message;
        }
        @Override public List<AgentInboundMessage> listInboundMessagesAwaitingDelivery(String channelId) {
            return inbound.values().stream().filter(value -> value.getChannelId().equals(channelId))
                    .filter(value -> value.getTaskId() != null)
                    .filter(value -> deliveries.values().stream().noneMatch(delivery -> delivery.getInboundMessageId().equals(value.getId())))
                    .toList();
        }
        @Override public AgentDeliveryCommand createOrGetDelivery(AgentDeliveryCommand command) {
            AgentDeliveryCommand existing = deliveries.values().stream()
                    .filter(value -> value.getInboundMessageId().equals(command.getInboundMessageId())).findFirst().orElse(null);
            if (existing != null) return existing; deliveries.put(command.getId(), command); return command;
        }
        @Override public List<AgentDeliveryCommand> claimDeliveries(String channelId, Date now, Date expiresAt, int limit) {
            List<AgentDeliveryCommand> result = deliveries.values().stream()
                    .filter(value -> value.getChannelId().equals(channelId))
                    .filter(value -> value.getStatus() == AgentDeliveryStatusEnum.PENDING
                            || value.getStatus() == AgentDeliveryStatusEnum.DELIVERING
                            && value.getLeaseExpiresAt().before(now)).limit(limit).toList();
            result.forEach(value -> { value.setStatus(AgentDeliveryStatusEnum.DELIVERING);
                value.setAttemptCount(value.getAttemptCount() + 1); value.setLeaseExpiresAt(expiresAt);
                value.setRevision(value.getRevision() + 1); });
            return result;
        }
        @Override public AgentDeliveryCommand getDelivery(String deliveryId) { return deliveries.get(deliveryId); }
        @Override public AgentDeliveryCommand updateDelivery(AgentDeliveryCommand command, long expectedRevision) {
            AgentDeliveryCommand current = deliveries.get(command.getId());
            if (current == null || current.getRevision() != expectedRevision) throw new ConcurrentModificationException();
            deliveries.put(command.getId(), command); return command;
        }
    }
}

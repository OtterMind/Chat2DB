package ai.chat2db.community.domain.core.impl.agent;

import ai.chat2db.community.domain.api.enums.agent.AgentDeliveryStatusEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentRunStatusEnum;
import ai.chat2db.community.domain.api.model.agent.AgentChatTaskCreation;
import ai.chat2db.community.domain.api.model.agent.AgentDefinition;
import ai.chat2db.community.domain.api.model.agent.AgentDeliveryCommand;
import ai.chat2db.community.domain.api.model.agent.AgentExternalConversationBinding;
import ai.chat2db.community.domain.api.model.agent.AgentGatewayChannel;
import ai.chat2db.community.domain.api.model.agent.AgentGatewayChannelCredential;
import ai.chat2db.community.domain.api.model.agent.AgentInboundMessage;
import ai.chat2db.community.domain.api.model.agent.AgentInboundMessageResult;
import ai.chat2db.community.domain.api.model.agent.AgentRun;
import ai.chat2db.community.domain.api.model.agent.AgentTask;
import ai.chat2db.community.domain.api.model.ai.ChatAttachment;
import ai.chat2db.community.domain.api.model.request.agent.AgentChatTaskCreateRequest;
import ai.chat2db.community.domain.api.model.request.agent.AgentGatewayChannelCreateRequest;
import ai.chat2db.community.domain.api.model.request.agent.AgentGatewayDeliveryReceiptRequest;
import ai.chat2db.community.domain.api.model.request.agent.AgentGatewayInboundRequest;
import ai.chat2db.community.domain.api.service.agent.IAgentChatTaskService;
import ai.chat2db.community.domain.api.service.agent.IAgentDefinitionService;
import ai.chat2db.community.domain.api.service.agent.IAgentGatewayBridgeService;
import ai.chat2db.community.domain.api.service.agent.IAgentTaskService;
import ai.chat2db.community.domain.api.service.ai.IAiChatHistoryService;
import ai.chat2db.community.domain.api.service.storage.IAgentGatewayStorage;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Base64;
import java.util.ConcurrentModificationException;
import java.util.Date;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

@Service
public class AgentGatewayBridgeServiceImpl implements IAgentGatewayBridgeService {

    static final int MAX_DELIVERY_ATTEMPTS = 5;
    static final long DELIVERY_LEASE_MILLIS = 60_000L;
    private static final int MAX_INBOUND_TEXT_LENGTH = 100_000;
    private static final int MAX_DELIVERY_CONTENT_LENGTH = 100_000;
    private static final int MAX_ATTACHMENTS = 10;
    private static final int MAX_ATTACHMENT_CONTENT_LENGTH = 32_000;

    private final IAgentGatewayStorage storage;
    private final IAgentDefinitionService agentService;
    private final IAgentChatTaskService chatTaskService;
    private final IAgentTaskService taskService;
    private final IAiChatHistoryService historyService;
    private final Clock clock;
    private final Supplier<String> tokenSupplier;

    @Autowired
    public AgentGatewayBridgeServiceImpl(IAgentGatewayStorage storage,
                                         IAgentDefinitionService agentService,
                                         IAgentChatTaskService chatTaskService,
                                         IAgentTaskService taskService,
                                         IAiChatHistoryService historyService) {
        this(storage, agentService, chatTaskService, taskService, historyService,
                Clock.systemUTC(), secureTokenSupplier());
    }

    AgentGatewayBridgeServiceImpl(IAgentGatewayStorage storage,
                                  IAgentDefinitionService agentService,
                                  IAgentChatTaskService chatTaskService,
                                  IAgentTaskService taskService,
                                  IAiChatHistoryService historyService,
                                  Clock clock, Supplier<String> tokenSupplier) {
        this.storage = storage;
        this.agentService = agentService;
        this.chatTaskService = chatTaskService;
        this.taskService = taskService;
        this.historyService = historyService;
        this.clock = clock;
        this.tokenSupplier = tokenSupplier;
    }

    @Override
    public AgentGatewayChannelCredential createChannel(AgentGatewayChannelCreateRequest request) {
        validateChannel(request);
        AgentDefinition agent = agentService.get(request.getDefaultAgentId());
        if (agent.getCreatedBy() != null && !Objects.equals(agent.getCreatedBy(), request.getCreatedBy())) {
            throw new SecurityException("gateway default agent is not accessible to the current user");
        }
        Date now = now();
        String token = tokenSupplier.get();
        AgentGatewayChannel channel = new AgentGatewayChannel();
        channel.setId(UUID.randomUUID().toString());
        channel.setName(request.getName().trim());
        channel.setPlatform(request.getPlatform());
        channel.setInstallationRef(request.getInstallationRef().trim());
        channel.setDefaultAgentId(request.getDefaultAgentId().trim());
        channel.setCreatedBy(request.getCreatedBy());
        channel.setEnabled(true);
        channel.setGmtCreate(now);
        channel.setGmtModified(now);
        channel.setRevision(1L);

        AgentGatewayChannelCredential credential = new AgentGatewayChannelCredential();
        credential.setChannel(storage.createGatewayChannel(channel, sha256(token)));
        credential.setGatewayToken(token);
        return credential;
    }

    @Override
    public List<AgentGatewayChannel> listChannels(Long ownerId) {
        if (ownerId == null) throw new IllegalArgumentException("gateway channel owner is required");
        return storage.listGatewayChannels(ownerId);
    }

    @Override
    public synchronized AgentInboundMessageResult acceptInbound(String channelId, String gatewayToken,
                                                                AgentGatewayInboundRequest request) {
        AgentGatewayChannel channel = authenticate(channelId, gatewayToken);
        validateInbound(request);
        String key = request.getIdempotencyKey().trim();
        String threadId = StringUtils.defaultString(StringUtils.trimToNull(request.getThreadId()));
        String selectedAgentId = StringUtils.defaultIfBlank(request.getAgentId(), channel.getDefaultAgentId()).trim();

        AgentInboundMessage existing = storage.getInboundMessage(channel.getId(), key);
        if (existing != null) {
            requireMatchingInbound(existing, request, selectedAgentId);
            return finishInbound(channel, existing, request, true);
        }

        AgentExternalConversationBinding binding = storage.getConversationBinding(
                channel.getId(), request.getChatId().trim(), threadId);
        if (binding == null) {
            String sessionId = historyService.createSession(channel.getCreatedBy(), request.getText()).getId();
            binding = new AgentExternalConversationBinding();
            Date now = now();
            binding.setId(UUID.randomUUID().toString());
            binding.setChannelId(channel.getId());
            binding.setChatId(request.getChatId().trim());
            binding.setThreadId(threadId);
            binding.setSessionId(sessionId);
            binding.setGmtCreate(now);
            binding.setGmtModified(now);
            binding.setRevision(1L);
            binding = storage.createConversationBinding(binding);
        }

        Date now = now();
        AgentInboundMessage inbound = new AgentInboundMessage();
        inbound.setId(UUID.randomUUID().toString());
        inbound.setChannelId(channel.getId());
        inbound.setBindingId(binding.getId());
        inbound.setEventId(request.getEventId().trim());
        inbound.setMessageId(request.getMessageId().trim());
        inbound.setIdempotencyKey(key);
        inbound.setSenderId(request.getSenderId().trim());
        inbound.setSenderDisplayName(StringUtils.trimToNull(request.getSenderDisplayName()));
        inbound.setText(request.getText().trim());
        inbound.setMentions(request.getMentions() == null ? List.of() : List.copyOf(request.getMentions()));
        inbound.setAttachments(sanitizeAttachments(request.getAttachments()));
        inbound.setAgentId(selectedAgentId);
        inbound.setReceivedAt(request.getReceivedAt() == null ? now : request.getReceivedAt());
        inbound.setGmtCreate(now);
        inbound.setGmtModified(now);
        inbound.setRevision(1L);
        inbound = storage.createInboundMessage(inbound);
        return finishInbound(channel, inbound, request, false);
    }

    @Override
    public List<AgentDeliveryCommand> claimDeliveries(String channelId, String gatewayToken, int limit) {
        AgentGatewayChannel channel = authenticate(channelId, gatewayToken);
        reconcileDeliveries(channel);
        int boundedLimit = Math.max(1, Math.min(limit, 20));
        Date now = now();
        return storage.claimDeliveries(channel.getId(), now,
                new Date(now.getTime() + DELIVERY_LEASE_MILLIS), boundedLimit);
    }

    @Override
    public AgentDeliveryCommand acknowledgeDelivery(String channelId, String gatewayToken, String deliveryId,
                                                     AgentGatewayDeliveryReceiptRequest request) {
        AgentGatewayChannel channel = authenticate(channelId, gatewayToken);
        if (request == null || request.getExpectedRevision() == null || request.getExpectedRevision() <= 0) {
            throw new IllegalArgumentException("positive expected delivery revision is required");
        }
        AgentDeliveryCommand current = storage.getDelivery(deliveryId);
        if (current == null || !channel.getId().equals(current.getChannelId())) {
            throw new NoSuchElementException("delivery command not found: " + deliveryId);
        }
        if (current.getStatus() == AgentDeliveryStatusEnum.DELIVERED
                && Boolean.TRUE.equals(request.getDelivered())
                && Objects.equals(current.getPlatformMessageId(), StringUtils.trimToNull(request.getPlatformMessageId()))) {
            return current;
        }
        if (!Objects.equals(current.getRevision(), request.getExpectedRevision())) {
            throw new ConcurrentModificationException("delivery revision has changed: " + deliveryId);
        }
        if (current.getStatus() != AgentDeliveryStatusEnum.DELIVERING) {
            throw new IllegalStateException("only a claimed delivery can be acknowledged");
        }
        Date now = now();
        AgentDeliveryCommand updated = copyDelivery(current);
        updated.setLeaseExpiresAt(null);
        updated.setGmtModified(now);
        updated.setRevision(current.getRevision() + 1);
        if (Boolean.TRUE.equals(request.getDelivered())) {
            if (StringUtils.isBlank(request.getPlatformMessageId())) {
                throw new IllegalArgumentException("delivered receipt requires platform message id");
            }
            updated.setStatus(AgentDeliveryStatusEnum.DELIVERED);
            updated.setPlatformMessageId(request.getPlatformMessageId().trim());
            updated.setDeliveredAt(now);
            updated.setLastError(null);
        } else {
            updated.setLastError(StringUtils.abbreviate(StringUtils.defaultIfBlank(request.getError(),
                    "gateway delivery failed"), 1024));
            if (current.getAttemptCount() >= MAX_DELIVERY_ATTEMPTS) {
                updated.setStatus(AgentDeliveryStatusEnum.DEAD_LETTER);
            } else {
                updated.setStatus(AgentDeliveryStatusEnum.PENDING);
                long delay = Math.min(300_000L, 1_000L << Math.min(current.getAttemptCount(), 8));
                updated.setNextAttemptAt(new Date(now.getTime() + delay));
            }
        }
        return storage.updateDelivery(updated, current.getRevision());
    }

    private AgentInboundMessageResult finishInbound(AgentGatewayChannel channel, AgentInboundMessage inbound,
                                                    AgentGatewayInboundRequest request, boolean duplicate) {
        AgentExternalConversationBinding binding = storage.getConversationBinding(channel.getId(),
                request.getChatId().trim(), StringUtils.defaultString(StringUtils.trimToNull(request.getThreadId())));
        if (binding == null || !binding.getId().equals(inbound.getBindingId())) {
            throw new IllegalStateException("inbound message conversation binding is unavailable");
        }
        AgentChatTaskCreation taskCreation = null;
        if (StringUtils.isBlank(inbound.getTaskId())) {
            AgentChatTaskCreateRequest task = new AgentChatTaskCreateRequest();
            task.setSessionId(binding.getSessionId());
            task.setMessageId(inbound.getId());
            task.setContent(inbound.getText());
            task.setTaskDescription(inbound.getText());
            task.setAssigneeAgentId(inbound.getAgentId());
            task.setAttachments(inbound.getAttachments());
            task.setCreatedBy(channel.getCreatedBy());
            taskCreation = chatTaskService.create(task);
            try {
                inbound = storage.attachInboundTask(inbound.getId(),
                        taskCreation.getTaskCreation().getTask().getId(), inbound.getRevision());
            } catch (ConcurrentModificationException conflict) {
                inbound = storage.getInboundMessage(channel.getId(), inbound.getIdempotencyKey());
                if (inbound == null || StringUtils.isBlank(inbound.getTaskId())) throw conflict;
            }
        }
        AgentInboundMessageResult result = new AgentInboundMessageResult();
        result.setBinding(binding);
        result.setInboundMessage(inbound);
        result.setTaskCreation(taskCreation);
        result.setDuplicate(duplicate);
        result.setConversationLinkStatus(conversationLinkStatus(channel, binding));
        result.setTaskLinkStatus(taskLinkStatus(channel, inbound.getTaskId()));
        return result;
    }

    private void reconcileDeliveries(AgentGatewayChannel channel) {
        for (AgentInboundMessage inbound : storage.listInboundMessagesAwaitingDelivery(channel.getId())) {
            AgentTask task;
            try {
                task = taskService.get(inbound.getTaskId());
            } catch (NoSuchElementException deleted) {
                storage.createOrGetDelivery(delivery(channel, inbound, null,
                        "任务已被删除，无法再打开原任务。"));
                continue;
            }
            AgentRun run = taskService.listRuns(task.getId()).stream()
                    .filter(candidate -> Objects.equals(candidate.getId(), task.getCurrentRunId()))
                    .findFirst().orElse(null);
            if (run == null || !terminal(run.getStatus())) continue;
            String content = deliveryContent(task, run);
            storage.createOrGetDelivery(delivery(channel, inbound, run, content));
        }
    }

    private AgentDeliveryCommand delivery(AgentGatewayChannel channel, AgentInboundMessage inbound,
                                           AgentRun run, String content) {
        AgentExternalConversationBinding binding = findBinding(channel, inbound);
        Date now = now();
        AgentDeliveryCommand command = new AgentDeliveryCommand();
        command.setId(UUID.randomUUID().toString());
        command.setChannelId(channel.getId());
        command.setInboundMessageId(inbound.getId());
        command.setTaskId(inbound.getTaskId());
        command.setRunId(run == null ? null : run.getId());
        command.setPlatform(channel.getPlatform());
        command.setInstallationRef(channel.getInstallationRef());
        command.setChatId(binding.getChatId());
        command.setThreadId(binding.getThreadId());
        command.setReplyToMessageId(inbound.getMessageId());
        command.setContent(StringUtils.left(content, MAX_DELIVERY_CONTENT_LENGTH));
        command.setIdempotencyKey("task:" + inbound.getTaskId() + ":final");
        command.setStatus(AgentDeliveryStatusEnum.PENDING);
        command.setAttemptCount(0);
        command.setNextAttemptAt(now);
        command.setGmtCreate(now);
        command.setGmtModified(now);
        command.setRevision(1L);
        return command;
    }

    private AgentExternalConversationBinding findBinding(AgentGatewayChannel channel, AgentInboundMessage inbound) {
        AgentExternalConversationBinding binding = storage.getConversationBinding(inbound.getBindingId());
        if (binding == null || !channel.getId().equals(binding.getChannelId())) {
            throw new IllegalStateException("delivery conversation binding is unavailable");
        }
        return binding;
    }

    private String deliveryContent(AgentTask task, AgentRun run) {
        if (run.getStatus() == AgentRunStatusEnum.COMPLETED) {
            return StringUtils.defaultIfBlank(run.getResultSummary(), "任务已完成：" + task.getTitle());
        }
        if (run.getStatus() == AgentRunStatusEnum.CANCELLED) return "任务已取消：" + task.getTitle();
        if (run.getStatus() == AgentRunStatusEnum.UNKNOWN) return "任务状态未知，请在 Chat2DB 中检查：" + task.getTitle();
        return "任务执行失败：" + StringUtils.defaultIfBlank(run.getFailureReason(), task.getTitle());
    }

    private String conversationLinkStatus(AgentGatewayChannel channel, AgentExternalConversationBinding binding) {
        if (binding.getArchivedAt() != null || channel.getArchivedAt() != null) return "ARCHIVED";
        return historyService.listSessions(channel.getCreatedBy()).stream()
                .anyMatch(session -> binding.getSessionId().equals(session.getId())) ? "ACTIVE" : "DELETED";
    }

    private String taskLinkStatus(AgentGatewayChannel channel, String taskId) {
        if (StringUtils.isBlank(taskId)) return "CREATING";
        try {
            AgentTask task = taskService.get(taskId);
            if (!Objects.equals(task.getCreatedBy(), channel.getCreatedBy())) return "DELETED";
            return task.getArchivedAt() == null ? "ACTIVE" : "ARCHIVED";
        } catch (NoSuchElementException exception) {
            return "DELETED";
        }
    }

    private AgentGatewayChannel authenticate(String channelId, String gatewayToken) {
        if (StringUtils.isBlank(channelId) || StringUtils.isBlank(gatewayToken)
                || !storage.matchesGatewayToken(channelId.trim(), sha256(gatewayToken.trim()))) {
            throw new SecurityException("invalid or inactive gateway channel credential");
        }
        AgentGatewayChannel channel = storage.getGatewayChannel(channelId.trim());
        if (channel == null) throw new SecurityException("gateway channel is unavailable");
        return channel;
    }

    private void requireMatchingInbound(AgentInboundMessage existing, AgentGatewayInboundRequest request,
                                        String selectedAgentId) {
        if (!existing.getEventId().equals(request.getEventId().trim())
                || !existing.getMessageId().equals(request.getMessageId().trim())
                || !existing.getSenderId().equals(request.getSenderId().trim())
                || !existing.getText().equals(request.getText().trim())
                || !existing.getAgentId().equals(selectedAgentId)
                || !Objects.equals(existing.getAttachments(), sanitizeAttachments(request.getAttachments()))) {
            throw new IllegalStateException("gateway idempotency key was reused with different message content");
        }
    }

    private void validateChannel(AgentGatewayChannelCreateRequest request) {
        if (request == null || request.getCreatedBy() == null) {
            throw new IllegalArgumentException("gateway channel owner is required");
        }
        if (StringUtils.isBlank(request.getName()) || request.getName().trim().length() > 128) {
            throw new IllegalArgumentException("gateway channel name is required and must not exceed 128 characters");
        }
        if (request.getPlatform() == null) throw new IllegalArgumentException("gateway platform is required");
        if (StringUtils.isBlank(request.getInstallationRef()) || request.getInstallationRef().trim().length() > 255) {
            throw new IllegalArgumentException("gateway installation reference is required and must not exceed 255 characters");
        }
        if (StringUtils.isBlank(request.getDefaultAgentId())) {
            throw new IllegalArgumentException("gateway default agent is required");
        }
    }

    private void validateInbound(AgentGatewayInboundRequest request) {
        if (request == null) throw new IllegalArgumentException("gateway inbound message is required");
        requireText(request.getChatId(), "chat id", 255);
        requireText(request.getMessageId(), "message id", 255);
        requireText(request.getEventId(), "event id", 255);
        requireText(request.getSenderId(), "sender id", 255);
        requireText(request.getIdempotencyKey(), "idempotency key", 255);
        requireText(request.getText(), "message text", MAX_INBOUND_TEXT_LENGTH);
        if (StringUtils.length(StringUtils.trimToNull(request.getThreadId())) > 255) {
            throw new IllegalArgumentException("thread id must not exceed 255 characters");
        }
        if (request.getMentions() != null && (request.getMentions().size() > 50
                || request.getMentions().stream().anyMatch(value -> StringUtils.length(value) > 128))) {
            throw new IllegalArgumentException("gateway message mentions exceed the supported limit");
        }
        sanitizeAttachments(request.getAttachments());
    }

    private List<ChatAttachment> sanitizeAttachments(List<ChatAttachment> attachments) {
        if (attachments == null) return List.of();
        if (attachments.size() > MAX_ATTACHMENTS) {
            throw new IllegalArgumentException("gateway message has too many attachments");
        }
        return attachments.stream().map(source -> {
            if (source == null || StringUtils.isBlank(source.getFileName())
                    || source.getFileName().length() > 255 || StringUtils.isBlank(source.getFileType())) {
                throw new IllegalArgumentException("gateway attachment name and MIME type are required");
            }
            ChatAttachment copy = new ChatAttachment();
            copy.setFileName(source.getFileName().trim());
            copy.setFileType(source.getFileType().trim());
            copy.setContentCategory(StringUtils.trimToNull(source.getContentCategory()));
            String content = StringUtils.defaultString(source.getContent());
            copy.setContent(StringUtils.left(content, MAX_ATTACHMENT_CONTENT_LENGTH));
            copy.setContentLength(source.getContentLength() == null ? content.length() : source.getContentLength());
            copy.setTruncated(Boolean.TRUE.equals(source.getTruncated())
                    || content.length() > MAX_ATTACHMENT_CONTENT_LENGTH);
            return copy;
        }).toList();
    }

    private void requireText(String value, String label, int maxLength) {
        if (StringUtils.isBlank(value) || value.trim().length() > maxLength) {
            throw new IllegalArgumentException(label + " is required and must not exceed " + maxLength + " characters");
        }
    }

    private boolean terminal(AgentRunStatusEnum status) {
        return status == AgentRunStatusEnum.COMPLETED || status == AgentRunStatusEnum.FAILED
                || status == AgentRunStatusEnum.CANCELLED || status == AgentRunStatusEnum.UNKNOWN;
    }

    private AgentDeliveryCommand copyDelivery(AgentDeliveryCommand source) {
        AgentDeliveryCommand copy = new AgentDeliveryCommand();
        copy.setId(source.getId()); copy.setChannelId(source.getChannelId());
        copy.setInboundMessageId(source.getInboundMessageId()); copy.setTaskId(source.getTaskId());
        copy.setRunId(source.getRunId()); copy.setPlatform(source.getPlatform());
        copy.setInstallationRef(source.getInstallationRef()); copy.setChatId(source.getChatId());
        copy.setThreadId(source.getThreadId()); copy.setReplyToMessageId(source.getReplyToMessageId());
        copy.setContent(source.getContent()); copy.setIdempotencyKey(source.getIdempotencyKey());
        copy.setAttachmentRefs(source.getAttachmentRefs());
        copy.setStatus(source.getStatus()); copy.setAttemptCount(source.getAttemptCount());
        copy.setNextAttemptAt(source.getNextAttemptAt()); copy.setLeaseExpiresAt(source.getLeaseExpiresAt());
        copy.setPlatformMessageId(source.getPlatformMessageId()); copy.setLastError(source.getLastError());
        copy.setDeliveredAt(source.getDeliveredAt()); copy.setGmtCreate(source.getGmtCreate());
        copy.setGmtModified(source.getGmtModified()); copy.setRevision(source.getRevision());
        return copy;
    }

    private Date now() {
        return Date.from(clock.instant());
    }

    private static Supplier<String> secureTokenSupplier() {
        SecureRandom random = new SecureRandom();
        return () -> {
            byte[] bytes = new byte[32];
            random.nextBytes(bytes);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        };
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}

package ai.chat2db.community.domain.core.impl.agent;

import ai.chat2db.community.domain.api.enums.agent.AgentRunStatusEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentTaskContextTypeEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentTaskOriginTypeEnum;
import ai.chat2db.community.domain.api.model.agent.AgentChatTaskCreation;
import ai.chat2db.community.domain.api.model.agent.AgentDefinition;
import ai.chat2db.community.domain.api.model.agent.AgentRun;
import ai.chat2db.community.domain.api.model.agent.AgentTask;
import ai.chat2db.community.domain.api.model.agent.AgentTaskContext;
import ai.chat2db.community.domain.api.model.agent.AgentTaskCreation;
import ai.chat2db.community.domain.api.model.ai.AiChatMessage;
import ai.chat2db.community.domain.api.model.ai.AiChatSession;
import ai.chat2db.community.domain.api.model.ai.ChatAttachment;
import ai.chat2db.community.domain.api.model.request.agent.AgentChatTaskCreateRequest;
import ai.chat2db.community.domain.api.model.request.agent.AgentTaskContextCreateRequest;
import ai.chat2db.community.domain.api.model.request.agent.AgentTaskCreateRequest;
import ai.chat2db.community.domain.api.model.request.ai.AiChatMessageAddRequest;
import ai.chat2db.community.domain.api.service.agent.IAgentChatTaskService;
import ai.chat2db.community.domain.api.service.agent.IAgentDefinitionService;
import ai.chat2db.community.domain.api.service.agent.IAgentRunCoordinator;
import ai.chat2db.community.domain.api.service.agent.IAgentTaskContextService;
import ai.chat2db.community.domain.api.service.agent.IAgentTaskService;
import ai.chat2db.community.domain.api.service.ai.IAiChatHistoryService;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

@Service
public class AgentChatTaskServiceImpl implements IAgentChatTaskService {

    private static final String TASK_DELEGATION = "TASK_DELEGATION";
    private static final String SNAPSHOT_TITLE = "Conversation snapshot";
    private static final int MAX_SNAPSHOT_MESSAGES = 10;
    private static final int MAX_SNAPSHOT_LENGTH = 32_000;
    private static final int MAX_ATTACHMENT_CONTENT_LENGTH = 32_000;

    private final IAiChatHistoryService historyService;
    private final IAgentDefinitionService agentService;
    private final IAgentTaskService taskService;
    private final IAgentTaskContextService contextService;
    private final IAgentRunCoordinator runCoordinator;

    public AgentChatTaskServiceImpl(IAiChatHistoryService historyService,
                                    IAgentDefinitionService agentService,
                                    IAgentTaskService taskService,
                                    IAgentTaskContextService contextService,
                                    IAgentRunCoordinator runCoordinator) {
        this.historyService = historyService;
        this.agentService = agentService;
        this.taskService = taskService;
        this.contextService = contextService;
        this.runCoordinator = runCoordinator;
    }

    @Override
    public synchronized AgentChatTaskCreation create(AgentChatTaskCreateRequest request) {
        validate(request);
        AgentDefinition agent = agentService.get(request.getAssigneeAgentId());
        if (agent.getCreatedBy() != null && !Objects.equals(agent.getCreatedBy(), request.getCreatedBy())) {
            throw new IllegalArgumentException("agent is not accessible to the current user");
        }

        AgentTask existingTask = findExistingTask(request.getMessageId(), request.getCreatedBy());
        if (existingTask != null) {
            return finishExisting(request, agent, existingTask);
        }

        AiChatSession session = resolveSession(request);
        List<AiChatMessage> priorMessages = historyService.getMessages(session.getId(), request.getCreatedBy());
        AgentTaskCreation taskCreation = taskService.create(taskRequest(request, session.getId()));
        appendSnapshot(taskCreation.getTask().getId(), request.getCreatedBy(), priorMessages);
        appendAttachments(taskCreation.getTask().getId(), request.getCreatedBy(), request.getAttachments());
        AiChatMessage message = appendDelegationMessage(request, agent, session.getId(), taskCreation.getTask().getId());
        dispatchIfQueued(taskCreation);
        return new AgentChatTaskCreation(session, message, taskCreation);
    }

    private AgentChatTaskCreation finishExisting(AgentChatTaskCreateRequest request, AgentDefinition agent,
                                                  AgentTask existingTask) {
        AiChatSession session = findSession(existingTask.getOriginSessionId(), request.getCreatedBy());
        if (session == null) {
            throw new IllegalStateException("origin chat session no longer exists");
        }
        List<AiChatMessage> priorMessages = historyService.getMessages(session.getId(), request.getCreatedBy())
                .stream()
                .filter(message -> !request.getMessageId().equals(message.getId()))
                .toList();
        ensureContexts(existingTask.getId(), request.getCreatedBy(), priorMessages, request.getAttachments());
        AiChatMessage message = appendDelegationMessage(
                request, agent, session.getId(), existingTask.getId());
        List<AgentRun> runs = taskService.listRuns(existingTask.getId());
        AgentRun initialRun = runs.stream()
                .filter(run -> Objects.equals(run.getId(), existingTask.getCurrentRunId()))
                .findFirst()
                .orElseGet(() -> runs.isEmpty() ? null : runs.get(0));
        AgentTaskCreation creation = new AgentTaskCreation(existingTask, initialRun);
        dispatchIfQueued(creation);
        return new AgentChatTaskCreation(session, message, creation);
    }

    private AgentTask findExistingTask(String messageId, Long createdBy) {
        return Stream.concat(taskService.list().stream(), taskService.listArchived().stream())
                .filter(task -> Objects.equals(task.getCreatedBy(), createdBy))
                .filter(task -> messageId.equals(task.getOriginMessageId()))
                .findFirst()
                .orElse(null);
    }

    private AiChatSession resolveSession(AgentChatTaskCreateRequest request) {
        if (StringUtils.isBlank(request.getSessionId())) {
            return historyService.createSession(request.getCreatedBy(), request.getTaskDescription());
        }
        AiChatSession session = findSession(request.getSessionId().trim(), request.getCreatedBy());
        if (session == null) {
            throw new IllegalArgumentException("chat session is not accessible to the current user");
        }
        return session;
    }

    private AiChatSession findSession(String sessionId, Long createdBy) {
        if (StringUtils.isBlank(sessionId)) return null;
        return historyService.listSessions(createdBy).stream()
                .filter(session -> sessionId.equals(session.getId()))
                .findFirst()
                .orElse(null);
    }

    private AgentTaskCreateRequest taskRequest(AgentChatTaskCreateRequest request, String sessionId) {
        AgentTaskCreateRequest taskRequest = new AgentTaskCreateRequest();
        String description = request.getTaskDescription().trim();
        String firstLine = description.lines().findFirst().orElse(description);
        taskRequest.setTitle(StringUtils.abbreviate(firstLine, 256));
        taskRequest.setDescription(description);
        taskRequest.setPriority(0);
        taskRequest.setAssigneeAgentId(request.getAssigneeAgentId());
        taskRequest.setCreatedBy(request.getCreatedBy());
        taskRequest.setOriginType(AgentTaskOriginTypeEnum.CHAT);
        taskRequest.setOriginSessionId(sessionId);
        taskRequest.setOriginMessageId(request.getMessageId().trim());
        taskRequest.setDataScopeSnapshot(request.getDataScopeSnapshot());
        return taskRequest;
    }

    private void appendSnapshot(String taskId, Long createdBy, List<AiChatMessage> messages) {
        String snapshot = snapshot(messages);
        if (StringUtils.isBlank(snapshot)) return;
        AgentTaskContextCreateRequest context = new AgentTaskContextCreateRequest();
        context.setTaskId(taskId);
        context.setType(AgentTaskContextTypeEnum.CHAT_SNAPSHOT);
        context.setTitle(SNAPSHOT_TITLE);
        context.setContent(snapshot);
        context.setCreatedBy(createdBy);
        contextService.append(context);
    }

    private void ensureContexts(String taskId, Long createdBy, List<AiChatMessage> messages,
                                List<ChatAttachment> attachments) {
        List<AgentTaskContext> existing = contextService.list(taskId);
        if (existing.stream().noneMatch(context -> context.getType() == AgentTaskContextTypeEnum.CHAT_SNAPSHOT)) {
            appendSnapshot(taskId, createdBy, messages);
        }
        List<String> existingAttachmentNames = existing.stream()
                .filter(context -> context.getType() == AgentTaskContextTypeEnum.ATTACHMENT)
                .map(AgentTaskContext::getAttachmentName)
                .filter(Objects::nonNull)
                .toList();
        List<ChatAttachment> missing = (attachments == null ? List.<ChatAttachment>of() : attachments).stream()
                .filter(attachment -> attachment != null
                        && !existingAttachmentNames.contains(attachment.getFileName()))
                .toList();
        appendAttachments(taskId, createdBy, missing);
    }

    private String snapshot(List<AiChatMessage> messages) {
        List<AiChatMessage> source = messages == null ? List.of() : messages;
        int from = Math.max(0, source.size() - MAX_SNAPSHOT_MESSAGES);
        List<String> entries = new ArrayList<>();
        int remaining = MAX_SNAPSHOT_LENGTH;
        for (int index = source.size() - 1; index >= from && remaining > 0; index--) {
            AiChatMessage message = source.get(index);
            if (StringUtils.isBlank(message.getContent())) continue;
            String role = "assistant".equalsIgnoreCase(message.getRole()) ? "Assistant" : "User";
            String entry = role + ": " + message.getContent().trim() + "\n";
            String retained = StringUtils.left(entry, remaining);
            entries.add(0, retained);
            remaining -= retained.length();
        }
        return String.join("", entries).trim();
    }

    private void appendAttachments(String taskId, Long createdBy, List<ChatAttachment> attachments) {
        for (ChatAttachment attachment : attachments == null ? List.<ChatAttachment>of() : attachments) {
            if (attachment == null || StringUtils.isBlank(attachment.getFileName())
                    || StringUtils.isBlank(attachment.getContent())) continue;
            AgentTaskContextCreateRequest context = new AgentTaskContextCreateRequest();
            context.setTaskId(taskId);
            context.setType(AgentTaskContextTypeEnum.ATTACHMENT);
            context.setTitle("Chat attachment");
            context.setContent(StringUtils.left(attachment.getContent(), MAX_ATTACHMENT_CONTENT_LENGTH));
            context.setAttachmentName(attachment.getFileName());
            context.setAttachmentMimeType(attachment.getFileType());
            context.setAttachmentSize(attachment.getContentLength() == null
                    ? null : attachment.getContentLength().longValue());
            context.setCreatedBy(createdBy);
            contextService.append(context);
        }
    }

    private AiChatMessage appendDelegationMessage(AgentChatTaskCreateRequest request, AgentDefinition agent,
                                                   String sessionId, String taskId) {
        AiChatMessageAddRequest message = new AiChatMessageAddRequest();
        message.setId(request.getMessageId().trim());
        message.setSessionId(sessionId);
        message.setUserId(request.getCreatedBy());
        message.setRole("user");
        message.setContent(request.getContent().trim());
        message.setAttachments(new ArrayList<>(request.getAttachments() == null
                ? List.of() : request.getAttachments()));
        message.setMessageType(TASK_DELEGATION);
        message.setTaskId(taskId);
        message.setAgentId(agent.getId());
        message.setAgentName(agent.getName());
        return historyService.addMessage(message);
    }

    private void dispatchIfQueued(AgentTaskCreation creation) {
        if (creation.getInitialRun() != null && creation.getInitialRun().getStatus() == AgentRunStatusEnum.QUEUED) {
            runCoordinator.dispatch(creation.getInitialRun().getId());
        }
    }

    private void validate(AgentChatTaskCreateRequest request) {
        if (request == null) throw new IllegalArgumentException("chat task request is required");
        if (request.getCreatedBy() == null) throw new IllegalArgumentException("chat task owner is required");
        if (StringUtils.isBlank(request.getMessageId()) || request.getMessageId().trim().length() > 128) {
            throw new IllegalArgumentException("chat task message id is required and must not exceed 128 characters");
        }
        if (StringUtils.isBlank(request.getContent())) {
            throw new IllegalArgumentException("chat task message content is required");
        }
        if (StringUtils.isBlank(request.getTaskDescription())) {
            throw new IllegalArgumentException("chat task description is required");
        }
        if (StringUtils.isBlank(request.getAssigneeAgentId())) {
            throw new IllegalArgumentException("chat task agent is required");
        }
    }
}

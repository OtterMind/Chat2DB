package ai.chat2db.community.domain.core.impl.agent;

import ai.chat2db.community.domain.api.enums.agent.AgentConnectorPairingStatusEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentConnectorSessionStatusEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentConnectorConversationStatusEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentConnectorInvocationStatusEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentConnectorExecutionModeEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentRuntimeEventTypeEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentRunStatusEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentStatusEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentTaskOriginTypeEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentTaskStatusEnum;
import ai.chat2db.community.domain.api.model.agent.AgentConnectorPairing;
import ai.chat2db.community.domain.api.model.agent.AgentConnectorPairingTicket;
import ai.chat2db.community.domain.api.model.agent.AgentConnectorContext;
import ai.chat2db.community.domain.api.model.agent.AgentConnectorDataWikiContext;
import ai.chat2db.community.domain.api.model.agent.AgentConnectorSession;
import ai.chat2db.community.domain.api.model.agent.AgentConnectorConversation;
import ai.chat2db.community.domain.api.model.agent.AgentConnectorInvocation;
import ai.chat2db.community.domain.api.model.agent.AgentConnectorAuditContext;
import ai.chat2db.community.domain.api.model.agent.AgentConnectorTokenGrant;
import ai.chat2db.community.domain.api.model.agent.AgentDefinition;
import ai.chat2db.community.domain.api.model.agent.AgentRun;
import ai.chat2db.community.domain.api.model.agent.AgentRunEvent;
import ai.chat2db.community.domain.api.model.agent.AgentRuntimeTaskScope;
import ai.chat2db.community.domain.api.model.agent.AgentTask;
import ai.chat2db.community.domain.api.model.agent.AgentTaskCreation;
import ai.chat2db.community.domain.api.model.request.agent.AgentConnectorPairingCreateRequest;
import ai.chat2db.community.domain.api.model.request.agent.AgentRunTransitionRequest;
import ai.chat2db.community.domain.api.model.request.agent.AgentTaskCreateRequest;
import ai.chat2db.community.domain.api.model.request.agent.AgentTaskTransitionRequest;
import ai.chat2db.community.domain.api.service.agent.IAgentConnectorService;
import ai.chat2db.community.domain.api.service.agent.IAgentDefinitionService;
import ai.chat2db.community.domain.api.service.agent.IAgentRunService;
import ai.chat2db.community.domain.api.service.agent.IAgentTaskService;
import ai.chat2db.community.domain.api.service.datawiki.IDataWikiService;
import ai.chat2db.community.domain.api.service.storage.IAgentConnectorStorage;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.Date;
import java.util.HexFormat;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.UUID;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class AgentConnectorServiceImpl implements IAgentConnectorService {
    private static final Duration PAIRING_TTL = Duration.ofMinutes(5);
    private static final Duration ACCESS_TTL = Duration.ofMinutes(10);
    private static final Duration REFRESH_TTL = Duration.ofDays(30);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final IAgentConnectorStorage storage;
    private final IAgentDefinitionService agentService;
    private final IAgentTaskService taskService;
    private final IAgentRunService runService;
    private final IDataWikiService dataWikiService;

    public AgentConnectorServiceImpl(IAgentConnectorStorage storage, IAgentDefinitionService agentService,
                                     IAgentTaskService taskService, IAgentRunService runService,
                                     IDataWikiService dataWikiService) {
        this.storage = storage;
        this.agentService = agentService;
        this.taskService = taskService;
        this.runService = runService;
        this.dataWikiService = dataWikiService;
    }

    @Override
    public AgentConnectorPairingTicket createPairing(AgentConnectorPairingCreateRequest request) {
        if (request == null || request.getProtocolVersion() == null || request.getProtocolVersion() != 1) {
            throw new IllegalArgumentException("Connector protocolVersion 1 is required");
        }
        String clientName = StringUtils.trimToNull(request.getClientName());
        if (clientName == null || clientName.length() > 128) {
            throw new IllegalArgumentException("clientName is required and must not exceed 128 characters");
        }
        String pollToken = token();
        Date now = new Date();
        AgentConnectorPairing pairing = new AgentConnectorPairing();
        pairing.setId(UUID.randomUUID().toString());
        pairing.setClientName(clientName);
        pairing.setPollTokenHash(hash(pollToken));
        pairing.setUserCode(userCode());
        pairing.setStatus(AgentConnectorPairingStatusEnum.PENDING);
        pairing.setCreatedAt(now);
        pairing.setExpiresAt(new Date(now.getTime() + PAIRING_TTL.toMillis()));
        pairing.setRevision(1L);
        storage.createPairing(pairing);

        AgentConnectorPairingTicket ticket = new AgentConnectorPairingTicket();
        ticket.setPairingId(pairing.getId());
        ticket.setPollToken(pollToken);
        ticket.setUserCode(pairing.getUserCode());
        ticket.setExpiresAt(pairing.getExpiresAt());
        ticket.setPollAfterMs(1000);
        return ticket;
    }

    @Override
    public AgentConnectorPairing pairingStatus(String pairingId, String pollToken) {
        AgentConnectorPairing pairing = requirePairing(pairingId);
        requireToken(pollToken, pairing.getPollTokenHash(), "pairing poll token");
        if (pairing.getStatus() == AgentConnectorPairingStatusEnum.PENDING
                && pairing.getExpiresAt().before(new Date())) {
            pairing.setStatus(AgentConnectorPairingStatusEnum.EXPIRED);
            pairing.setRevision(pairing.getRevision() + 1);
            pairing = storage.updatePairing(pairing, pairing.getRevision() - 1);
        }
        return pairing;
    }

    @Override
    public List<AgentConnectorPairing> listPendingPairings() {
        Date now = new Date();
        return storage.listPendingPairings().stream().filter(value -> value.getExpiresAt().after(now)).toList();
    }

    @Override
    public AgentConnectorPairing decidePairing(String pairingId, String agentId, boolean approved,
                                                long expectedRevision, Long ownerId) {
        AgentConnectorPairing pairing = requirePairing(pairingId);
        if (pairing.getRevision() != expectedRevision) {
            throw new IllegalStateException("pairing revision changed; refresh before deciding");
        }
        if (pairing.getStatus() != AgentConnectorPairingStatusEnum.PENDING || pairing.getExpiresAt().before(new Date())) {
            throw new IllegalStateException("only an active pending pairing can be decided");
        }
        pairing.setOwnerId(ownerId);
        pairing.setDecidedAt(new Date());
        if (approved) {
            AgentDefinition agent = agentService.get(agentId);
            if (agent.getStatus() != AgentStatusEnum.ACTIVE || !visibleTo(agent, ownerId)) {
                throw new SecurityException("selected Agent is unavailable");
            }
            if (effectiveScopes(agent).isEmpty()) {
                throw new IllegalStateException("selected Agent has no bound data scope or DataWiki");
            }
            pairing.setAgentId(agent.getId());
            pairing.setAgentName(agent.getName());
            pairing.setExchangeCode(token());
            pairing.setStatus(AgentConnectorPairingStatusEnum.APPROVED);
        } else {
            pairing.setStatus(AgentConnectorPairingStatusEnum.DENIED);
        }
        long revision = pairing.getRevision();
        pairing.setRevision(revision + 1);
        return storage.updatePairing(pairing, revision);
    }

    @Override
    public AgentConnectorTokenGrant exchange(String pairingId, String pollToken, String exchangeCode) {
        AgentConnectorPairing pairing = pairingStatus(pairingId, pollToken);
        if (pairing.getStatus() != AgentConnectorPairingStatusEnum.APPROVED
                || !constantEquals(exchangeCode, pairing.getExchangeCode())) {
            throw new SecurityException("pairing exchange authorization failed");
        }
        AgentDefinition agent = agentService.get(pairing.getAgentId());
        if (agent.getStatus() != AgentStatusEnum.ACTIVE || !visibleTo(agent, pairing.getOwnerId())) {
            throw new SecurityException("paired Agent is no longer available");
        }
        String sessionId = UUID.randomUUID().toString();
        String accessToken = token();
        String refreshToken = token();
        Date now = new Date();
        AgentConnectorSession session = new AgentConnectorSession();
        session.setId(sessionId);
        session.setClientName(pairing.getClientName());
        session.setAgentId(agent.getId());
        session.setAgentName(agent.getName());
        session.setOwnerId(pairing.getOwnerId());
        session.setStatus(AgentConnectorSessionStatusEnum.ACTIVE);
        session.setAccessTokenHash(hash(accessToken));
        session.setRefreshTokenHash(hash(refreshToken));
        session.setAccessTokenExpiresAt(new Date(now.getTime() + ACCESS_TTL.toMillis()));
        session.setRefreshTokenExpiresAt(new Date(now.getTime() + REFRESH_TTL.toMillis()));
        session.setCreatedAt(now);
        session.setLastUsedAt(now);
        session.setRevision(1L);
        session = storage.createSession(session);

        long revision = pairing.getRevision();
        pairing.setSessionId(session.getId());
        pairing.setExchangeCode(null);
        pairing.setStatus(AgentConnectorPairingStatusEnum.EXCHANGED);
        pairing.setRevision(revision + 1);
        storage.updatePairing(pairing, revision);
        return grant(session, accessToken, refreshToken);
    }

    @Override
    public synchronized AgentConnectorTokenGrant refresh(String sessionId, String refreshToken) {
        AgentConnectorSession session = requireActiveSession(sessionId);
        requireToken(refreshToken, session.getRefreshTokenHash(), "refresh token");
        if (session.getRefreshTokenExpiresAt().before(new Date())) {
            expire(session);
            throw new SecurityException("Connector Session refresh token expired");
        }
        String nextAccess = token();
        String nextRefresh = token();
        Date now = new Date();
        long revision = session.getRevision();
        session.setAccessTokenHash(hash(nextAccess));
        session.setRefreshTokenHash(hash(nextRefresh));
        session.setAccessTokenExpiresAt(new Date(now.getTime() + ACCESS_TTL.toMillis()));
        session.setRefreshTokenExpiresAt(new Date(now.getTime() + REFRESH_TTL.toMillis()));
        session.setRevision(revision + 1);
        session = storage.updateSession(session, revision);
        return grant(session, nextAccess, nextRefresh);
    }

    @Override
    public synchronized AgentConnectorSession revoke(String sessionId, Long ownerId) {
        AgentConnectorSession session = requireSession(sessionId);
        if (!Objects.equals(session.getOwnerId(), ownerId)) throw new SecurityException("Connector Session owner mismatch");
        if (session.getStatus() == AgentConnectorSessionStatusEnum.REVOKED
                || session.getStatus() == AgentConnectorSessionStatusEnum.EXPIRED) {
            closeRunAndTask(session);
            session.setPendingApprovalCount(0);
            session.setConversationCount(storage.listConversations(session.getId()).size());
            return session;
        }
        long revision = session.getRevision();
        session.setStatus(AgentConnectorSessionStatusEnum.REVOKED);
        session.setRevokedAt(new Date());
        session.setRevision(revision + 1);
        AgentConnectorSession result = storage.updateSession(session, revision);
        closeRunAndTask(result);
        result.setPendingApprovalCount(0);
        result.setConversationCount(storage.listConversations(result.getId()).size());
        return result;
    }

    @Override
    public synchronized void deleteSession(String sessionId, Long ownerId) {
        AgentConnectorSession session = requireSession(sessionId);
        if (!Objects.equals(session.getOwnerId(), ownerId)) {
            throw new SecurityException("Connector Session owner mismatch");
        }
        if (session.getStatus() == AgentConnectorSessionStatusEnum.ACTIVE) {
            throw new IllegalStateException("an active Connector Session must be stopped before deletion");
        }
        closeRunAndTask(session);
        LinkedHashSet<String> taskIds = new LinkedHashSet<>();
        if (StringUtils.isNotBlank(session.getTaskId())) taskIds.add(session.getTaskId());
        storage.listConversations(sessionId).stream().map(AgentConnectorConversation::getTaskId)
                .filter(StringUtils::isNotBlank).forEach(taskIds::add);
        storage.deleteSession(sessionId);
        for (String taskId : taskIds) deleteArchivedAuditTask(taskId);
    }

    @Override
    public List<AgentConnectorSession> listSessions(Long ownerId) {
        List<AgentConnectorSession> sessions = storage.listSessions(ownerId);
        sessions.forEach(session -> {
            List<AgentConnectorConversation> conversations = storage.listConversations(session.getId());
            session.setConversationCount(conversations.size());
            session.setPendingApprovalCount(session.getStatus() == AgentConnectorSessionStatusEnum.ACTIVE
                    ? pendingApprovalCount(session, conversations) : 0);
        });
        return sessions;
    }

    @Override
    public List<AgentConnectorConversation> listConversations(String sessionId, Long ownerId) {
        AgentConnectorSession session = requireSession(sessionId);
        if (!Objects.equals(session.getOwnerId(), ownerId)) {
            throw new SecurityException("Connector Session owner mismatch");
        }
        List<AgentConnectorConversation> conversations = storage.listConversations(sessionId);
        conversations.forEach(conversation -> conversation.setPendingApprovalCount(
                session.getStatus() == AgentConnectorSessionStatusEnum.ACTIVE
                        ? pendingApprovalCount(conversation) : 0));
        return conversations;
    }

    @Override
    public boolean isConnectorTask(String taskId) {
        return storage.getSessionByTaskId(taskId) != null;
    }

    @Override
    public AgentConnectorAuditContext auditContext(String taskId) {
        AgentConnectorSession session = storage.getSessionByTaskId(taskId);
        if (session == null) return null;
        AgentConnectorAuditContext context = new AgentConnectorAuditContext();
        context.setExecutionMode(AgentConnectorExecutionModeEnum.EXTERNAL_RUNTIME_DELEGATION);
        context.setExternalRuntimeName(session.getClientName());
        context.setAuthorizationAgentId(session.getAgentId());
        context.setAuthorizationAgentName(session.getAgentName());
        return context;
    }

    @Override
    public synchronized int reconcileSessions(Date idleBefore, int limit) {
        if (idleBefore == null || limit <= 0) return 0;
        int reconciled = 0;
        for (AgentConnectorSession session : storage.listActiveSessionsBefore(idleBefore, limit)) {
            try {
                long revision = session.getRevision();
                session.setStatus(AgentConnectorSessionStatusEnum.EXPIRED);
                session.setRevokedAt(new Date());
                session.setRevision(revision + 1);
                AgentConnectorSession expired = storage.updateSession(session, revision);
                closeRunAndTask(expired);
                reconciled++;
            } catch (RuntimeException ignored) {
                // A concurrent refresh or revoke wins; the next reconciliation will observe it.
            }
        }
        for (AgentConnectorSession session : storage.listAllSessions()) {
            if (session.getStatus() != AgentConnectorSessionStatusEnum.ACTIVE) closeRunAndTask(session);
        }
        return reconciled;
    }

    @Override
    public AgentRuntimeTaskScope authorizeAccess(String sessionId, String accessToken) {
        AgentConnectorSession session = requireActiveSession(sessionId);
        requireToken(accessToken, session.getAccessTokenHash(), "access token");
        if (session.getAccessTokenExpiresAt().before(new Date())) {
            throw new SecurityException("Connector Session access token expired");
        }
        AgentDefinition agent = agentService.get(session.getAgentId());
        if (agent.getStatus() != AgentStatusEnum.ACTIVE || !visibleTo(agent, session.getOwnerId())) {
            throw new SecurityException("Connector Session Agent is unavailable");
        }
        AgentRuntimeTaskScope scope = new AgentRuntimeTaskScope();
        scope.setRunId(session.getRunId()); scope.setTaskId(session.getTaskId()); scope.setAgentId(agent.getId());
        scope.setTaskOwnerId(session.getOwnerId()); scope.setExpiresAt(session.getAccessTokenExpiresAt());
        scope.setDataScopes(effectiveScopes(agent));
        scope.setDataWikiIds(agent.getDataWikiIds() == null ? List.of() : List.copyOf(agent.getDataWikiIds()));
        scope.setConnectorSessionId(session.getId());
        return scope;
    }

    @Override
    public synchronized AgentRuntimeTaskScope authorizeToolCall(String sessionId, String accessToken,
            String externalSessionId, String externalCallId, String toolName, String arguments) {
        AgentRuntimeTaskScope base = authorizeAccess(sessionId, accessToken);
        AgentConnectorSession session = requireActiveSession(sessionId);
        boolean hasExternalSession = StringUtils.isNotBlank(externalSessionId);
        boolean hasExternalCall = StringUtils.isNotBlank(externalCallId);
        if (!hasExternalSession && !hasExternalCall) {
            if (StringUtils.isNotBlank(session.getTaskId()) && StringUtils.isNotBlank(session.getRunId())) {
                touchSession(session);
                return base;
            }
            return createLegacyAuditScope(session, base);
        }
        if (!hasExternalSession || !hasExternalCall) {
            throw new IllegalArgumentException("external session id and external call id must be provided together");
        }
        String externalSession = requiredCorrelation(externalSessionId, "external session id", 255);
        String externalCall = requiredCorrelation(externalCallId, "external call id", 255);
        String normalizedTool = requiredCorrelation(toolName, "tool name", 128);
        touchSession(session);

        AgentConnectorConversation conversation = storage.getConversation(sessionId, externalSession);
        AgentTaskCreation taskCreation = null;
        if (conversation == null) {
            AgentDefinition agent = agentService.get(session.getAgentId());
            AgentTaskCreateRequest request = new AgentTaskCreateRequest();
            request.setTitle("Connector: " + session.getClientName() + " · " + shortId(externalSession));
            request.setDescription(externalDelegationDescription(session, agent)
                    + " External conversation: " + externalSession);
            request.setAssigneeAgentId(agent.getId());
            request.setCreatedBy(session.getOwnerId());
            request.setOriginType(AgentTaskOriginTypeEnum.CONNECTOR);
            request.setOriginSessionId(externalSession);
            request.setOriginMessageId(sessionId);
            request.setDataScopeSnapshot(effectiveScopes(agent));
            taskCreation = taskService.create(request);
            moveTask(taskCreation.getTask(), AgentTaskStatusEnum.IN_PROGRESS);
            Date now = new Date();
            conversation = new AgentConnectorConversation();
            conversation.setId(UUID.randomUUID().toString());
            conversation.setConnectorSessionId(sessionId);
            conversation.setExternalSessionId(externalSession);
            conversation.setTaskId(taskCreation.getTask().getId());
            conversation.setStatus(AgentConnectorConversationStatusEnum.ACTIVE);
            conversation.setCreatedAt(now);
            conversation.setLastUsedAt(now);
            conversation.setRevision(1L);
            conversation = storage.createConversation(conversation);
        } else {
            touchConversation(conversation);
        }

        AgentConnectorInvocation invocation = storage.getInvocation(conversation.getId(), externalCall);
        if (invocation == null) {
            AgentRun run;
            if (taskCreation != null) {
                run = moveRun(taskCreation.getInitialRun(), AgentRunStatusEnum.RUNNING);
            } else {
                run = moveRun(taskService.createConnectorRun(conversation.getTaskId(), session.getAgentId())
                        .getInitialRun(), AgentRunStatusEnum.RUNNING);
            }
            Date now = new Date();
            invocation = new AgentConnectorInvocation();
            invocation.setId(UUID.randomUUID().toString());
            invocation.setConversationId(conversation.getId());
            invocation.setExternalCallId(externalCall);
            invocation.setToolName(normalizedTool);
            invocation.setTaskId(conversation.getTaskId());
            invocation.setRunId(run.getId());
            invocation.setStatus(AgentConnectorInvocationStatusEnum.RUNNING);
            invocation.setCreatedAt(now);
            invocation.setUpdatedAt(now);
            invocation.setRevision(1L);
            invocation = storage.createInvocation(invocation);
            appendToolEvent(invocation, AgentRuntimeEventTypeEnum.TOOL_CALL, normalizedTool,
                    Map.of("toolCallId", externalCall, "name", normalizedTool,
                            "arguments", truncate(arguments, 20_000)));
        } else if (!Objects.equals(invocation.getToolName(), normalizedTool)) {
            throw new IllegalStateException("external call id is already bound to another tool");
        }

        base.setRunId(invocation.getRunId());
        base.setTaskId(invocation.getTaskId());
        base.setConnectorConversationId(conversation.getId());
        base.setConnectorInvocationId(invocation.getId());
        base.setExternalSessionId(externalSession);
        base.setExternalCallId(externalCall);
        if (invocation.getStatus() == AgentConnectorInvocationStatusEnum.COMPLETED
                || invocation.getStatus() == AgentConnectorInvocationStatusEnum.FAILED) {
            base.setConnectorReplayResultJson(invocation.getResponseJson());
        }
        return base;
    }

    @Override
    public AgentRuntimeTaskScope authorizeInvocation(String sessionId, String accessToken,
            String externalSessionId, String externalCallId) {
        AgentRuntimeTaskScope base = authorizeAccess(sessionId, accessToken);
        AgentConnectorSession session = requireActiveSession(sessionId);
        boolean hasExternalSession = StringUtils.isNotBlank(externalSessionId);
        boolean hasExternalCall = StringUtils.isNotBlank(externalCallId);
        if (!hasExternalSession && !hasExternalCall
                && StringUtils.isNotBlank(session.getTaskId()) && StringUtils.isNotBlank(session.getRunId())) {
            return base;
        }
        if (!hasExternalSession || !hasExternalCall) {
            throw new IllegalArgumentException("external session id and external call id are required");
        }
        AgentConnectorConversation conversation = storage.getConversation(sessionId,
                requiredCorrelation(externalSessionId, "external session id", 255));
        if (conversation == null) throw new SecurityException("Connector conversation not found");
        AgentConnectorInvocation invocation = storage.getInvocation(conversation.getId(),
                requiredCorrelation(externalCallId, "external call id", 255));
        if (invocation == null) throw new SecurityException("Connector invocation not found");
        base.setRunId(invocation.getRunId());
        base.setTaskId(invocation.getTaskId());
        base.setConnectorConversationId(conversation.getId());
        base.setConnectorInvocationId(invocation.getId());
        base.setExternalSessionId(conversation.getExternalSessionId());
        base.setExternalCallId(invocation.getExternalCallId());
        return base;
    }

    @Override
    public synchronized void completeToolCall(AgentRuntimeTaskScope scope, boolean success,
            boolean waitingApproval, String result) {
        if (scope == null || StringUtils.isBlank(scope.getConnectorInvocationId())) return;
        AgentConnectorInvocation invocation = storage.getInvocation(scope.getConnectorConversationId(),
                scope.getExternalCallId());
        if (invocation == null || !Objects.equals(invocation.getId(), scope.getConnectorInvocationId())) return;
        if (invocation.getStatus() == AgentConnectorInvocationStatusEnum.COMPLETED
                || invocation.getStatus() == AgentConnectorInvocationStatusEnum.FAILED) return;
        Date now = new Date();
        long revision = invocation.getRevision();
        invocation.setUpdatedAt(now);
        if (waitingApproval) {
            invocation.setStatus(AgentConnectorInvocationStatusEnum.WAITING_APPROVAL);
        } else {
            invocation.setStatus(success ? AgentConnectorInvocationStatusEnum.COMPLETED
                    : AgentConnectorInvocationStatusEnum.FAILED);
            invocation.setCompletedAt(now);
            invocation.setResponseJson(result);
        }
        invocation.setRevision(revision + 1);
        storage.updateInvocation(invocation, revision);
        if (waitingApproval) return;

        appendToolEvent(invocation, AgentRuntimeEventTypeEnum.TOOL_RESULT,
                truncate(result, 20_000),
                Map.of("toolCallId", invocation.getExternalCallId(), "name", invocation.getToolName(),
                        "success", success, "status", success ? "COMPLETED" : "FAILED"));
        AgentRun run = runService.get(invocation.getRunId());
        if (run.getStatus() == AgentRunStatusEnum.WAITING_APPROVAL) {
            run = moveRun(run, AgentRunStatusEnum.RUNNING);
        }
        if (run.getStatus() == AgentRunStatusEnum.QUEUED) run = moveRun(run, AgentRunStatusEnum.RUNNING);
        if (run.getStatus() == AgentRunStatusEnum.RUNNING) {
            moveRun(run, success ? AgentRunStatusEnum.COMPLETED : AgentRunStatusEnum.FAILED,
                    success ? null : "Connector tool call failed");
        }
    }

    @Override
    public AgentConnectorContext context(String sessionId, String accessToken) {
        authorizeAccess(sessionId, accessToken);
        AgentConnectorSession session = requireActiveSession(sessionId);
        AgentDefinition agent = agentService.get(session.getAgentId());
        AgentConnectorContext result = new AgentConnectorContext();
        result.setSessionId(session.getId());
        result.setSessionStatus(session.getStatus());
        result.setLastUsedAt(session.getLastUsedAt());
        result.setRefreshTokenExpiresAt(session.getRefreshTokenExpiresAt());
        result.setAgentId(agent.getId());
        result.setAgentName(agent.getName());
        result.setAgentAvatar(agent.getAvatar());
        result.setExecutionMode(AgentConnectorExecutionModeEnum.EXTERNAL_RUNTIME_DELEGATION);
        result.setExternalRuntimeName(session.getClientName());
        result.setRuntimeType(agent.getRuntimeType());
        result.setRuntimeProfileId(agent.getRuntimeProfileId());
        result.setCapabilities(agent.getCapabilities() == null
                ? new LinkedHashSet<>() : new LinkedHashSet<>(agent.getCapabilities()));
        result.setExplicitDataScopes(AgentScopePolicy.copyScopes(agent.getDataScopes()));
        result.setEffectiveDataScopes(AgentScopePolicy.copyScopes(effectiveScopes(agent)));
        result.setDataWikis(dataWikiContexts(agent));
        return result;
    }

    private List<AgentConnectorDataWikiContext> dataWikiContexts(AgentDefinition agent) {
        return (agent.getDataWikiBindings() == null ? List.<ai.chat2db.community.domain.api.model.agent.AgentDataWikiBinding>of()
                : agent.getDataWikiBindings()).stream().map(binding -> {
            try {
                var wiki = dataWikiService.get(binding.getDataWikiId());
                AgentConnectorDataWikiContext context = new AgentConnectorDataWikiContext();
                context.setId(wiki.getId());
                context.setName(wiki.getName());
                context.setDescription(wiki.getDescription());
                context.setTableCount(wiki.getResources() == null ? 0 : wiki.getResources().size());
                context.setDataSourceCount(wiki.getResources() == null ? 0 : (int) wiki.getResources().stream()
                        .map(resource -> resource.getDataSourceId()).filter(Objects::nonNull).distinct().count());
                context.setMaxRows(binding.getMaxRows());
                context.setTimeoutSeconds(binding.getTimeoutSeconds());
                context.setApprovalMode(binding.getApprovalMode() == null ? null : binding.getApprovalMode().name());
                context.setAllowProduction(Boolean.TRUE.equals(binding.getAllowProduction()));
                return context;
            } catch (RuntimeException ignored) {
                return null;
            }
        }).filter(Objects::nonNull).toList();
    }

    private AgentConnectorTokenGrant grant(AgentConnectorSession session, String access, String refresh) {
        AgentConnectorTokenGrant grant = new AgentConnectorTokenGrant();
        grant.setSessionId(session.getId()); grant.setAgentId(session.getAgentId()); grant.setAgentName(session.getAgentName());
        grant.setMcpEndpoint("/api/agent/connectors/mcp/sessions/" + session.getId());
        grant.setAccessToken(access); grant.setAccessTokenExpiresAt(session.getAccessTokenExpiresAt());
        grant.setRefreshToken(refresh); grant.setRefreshTokenExpiresAt(session.getRefreshTokenExpiresAt());
        return grant;
    }

    private void expire(AgentConnectorSession session) {
        long revision = session.getRevision(); session.setStatus(AgentConnectorSessionStatusEnum.EXPIRED);
        session.setRevokedAt(new Date());
        session.setRevision(revision + 1); storage.updateSession(session, revision); closeRunAndTask(session);
    }

    private void closeRunAndTask(AgentConnectorSession session) {
        for (AgentConnectorConversation conversation : storage.listConversations(session.getId())) {
            closeConversation(conversation);
        }
        if (StringUtils.isBlank(session.getRunId()) || StringUtils.isBlank(session.getTaskId())) return;
        try {
            AgentRun run = runService.get(session.getRunId());
            if (List.of(AgentRunStatusEnum.QUEUED, AgentRunStatusEnum.DISPATCHED,
                    AgentRunStatusEnum.RUNNING, AgentRunStatusEnum.WAITING_APPROVAL).contains(run.getStatus())) {
                moveRun(run, AgentRunStatusEnum.CANCELLED);
            }
            AgentTask task = taskService.get(session.getTaskId());
            if (task.getStatus() == AgentTaskStatusEnum.WAITING_APPROVAL) {
                task = moveTask(task, AgentTaskStatusEnum.IN_PROGRESS);
            }
            if (task.getStatus() == AgentTaskStatusEnum.IN_PROGRESS) {
                task = moveTask(task, AgentTaskStatusEnum.IN_REVIEW);
            }
            if (task.getStatus() == AgentTaskStatusEnum.IN_REVIEW) {
                task = moveTask(task, AgentTaskStatusEnum.DONE);
            }
            if (task.getArchivedAt() == null) {
                taskService.archive(task.getId(), task.getRevision());
            }
        } catch (RuntimeException ignored) {
            // Session revocation must remain effective even if its audit Task was already closed.
        }
    }

    private void closeConversation(AgentConnectorConversation conversation) {
        for (AgentConnectorInvocation invocation : storage.listInvocations(conversation.getId())) {
            try {
                AgentRun run = runService.get(invocation.getRunId());
                if (List.of(AgentRunStatusEnum.QUEUED, AgentRunStatusEnum.DISPATCHED,
                        AgentRunStatusEnum.RUNNING, AgentRunStatusEnum.WAITING_APPROVAL).contains(run.getStatus())) {
                    moveRun(run, AgentRunStatusEnum.CANCELLED);
                }
            } catch (RuntimeException ignored) {
                // Continue closing the remaining audit records.
            }
            if (invocation.getStatus() != AgentConnectorInvocationStatusEnum.COMPLETED
                    && invocation.getStatus() != AgentConnectorInvocationStatusEnum.FAILED) {
                try {
                    long revision = invocation.getRevision();
                    invocation.setStatus(AgentConnectorInvocationStatusEnum.FAILED);
                    invocation.setUpdatedAt(new Date());
                    invocation.setCompletedAt(invocation.getUpdatedAt());
                    invocation.setResponseJson("{\"content\":[{\"type\":\"text\",\"text\":\"Connector Session closed\"}],\"isError\":true}");
                    invocation.setRevision(revision + 1);
                    storage.updateInvocation(invocation, revision);
                } catch (RuntimeException ignored) {
                    // A concurrent tool completion wins.
                }
            }
        }
        try {
            AgentTask task = taskService.get(conversation.getTaskId());
            if (task.getStatus() == AgentTaskStatusEnum.WAITING_APPROVAL) {
                task = moveTask(task, AgentTaskStatusEnum.IN_PROGRESS);
            }
            if (task.getStatus() == AgentTaskStatusEnum.IN_PROGRESS) task = moveTask(task, AgentTaskStatusEnum.IN_REVIEW);
            if (task.getStatus() == AgentTaskStatusEnum.IN_REVIEW) task = moveTask(task, AgentTaskStatusEnum.DONE);
            if (task.getArchivedAt() == null) taskService.archive(task.getId(), task.getRevision());
        } catch (RuntimeException ignored) {
            // Session revocation remains authoritative if an audit Task was already closed.
        }
        if (conversation.getStatus() == AgentConnectorConversationStatusEnum.ACTIVE) {
            try {
                long revision = conversation.getRevision();
                conversation.setStatus(AgentConnectorConversationStatusEnum.CLOSED);
                conversation.setClosedAt(new Date());
                conversation.setRevision(revision + 1);
                storage.updateConversation(conversation, revision);
            } catch (RuntimeException ignored) {
                // A concurrent close wins.
            }
        }
    }

    private int pendingApprovalCount(AgentConnectorSession session,
                                     List<AgentConnectorConversation> conversations) {
        LinkedHashSet<String> runIds = new LinkedHashSet<>();
        if (StringUtils.isNotBlank(session.getRunId())) runIds.add(session.getRunId());
        for (AgentConnectorConversation conversation : conversations) {
            storage.listInvocations(conversation.getId()).stream().map(AgentConnectorInvocation::getRunId)
                    .filter(StringUtils::isNotBlank).forEach(runIds::add);
        }
        return (int) runIds.stream().filter(this::isWaitingApproval).count();
    }

    private int pendingApprovalCount(AgentConnectorConversation conversation) {
        return (int) storage.listInvocations(conversation.getId()).stream()
                .map(AgentConnectorInvocation::getRunId).filter(StringUtils::isNotBlank).distinct()
                .filter(this::isWaitingApproval).count();
    }

    private boolean isWaitingApproval(String runId) {
        try {
            return runService.get(runId).getStatus() == AgentRunStatusEnum.WAITING_APPROVAL;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private void deleteArchivedAuditTask(String taskId) {
        try {
            AgentTask task = taskService.get(taskId);
            if (task.getArchivedAt() != null) taskService.deleteArchived(taskId, task.getRevision());
        } catch (NoSuchElementException ignored) {
            // A previously cleaned audit Task does not block deleting its Connector Session.
        }
    }

    private AgentRun moveRun(AgentRun run, AgentRunStatusEnum target) {
        return moveRun(run, target, null, target == AgentRunStatusEnum.COMPLETED
                ? "Connector tool call completed" : null);
    }

    private AgentRun moveRun(AgentRun run, AgentRunStatusEnum target, String failureReason) {
        return moveRun(run, target, failureReason, null);
    }

    private AgentRun moveRun(AgentRun run, AgentRunStatusEnum target, String failureReason, String resultSummary) {
        AgentRunTransitionRequest request = new AgentRunTransitionRequest();
        request.setRunId(run.getId()); request.setExpectedRevision(run.getRevision()); request.setTargetStatus(target);
        request.setFailureReason(failureReason);
        request.setResultSummary(resultSummary);
        return runService.transition(request);
    }

    private void touchSession(AgentConnectorSession session) {
        Date now = new Date();
        long revision = session.getRevision();
        session.setLastUsedAt(now);
        session.setRevision(revision + 1);
        storage.updateSession(session, revision);
    }

    private AgentRuntimeTaskScope createLegacyAuditScope(AgentConnectorSession session, AgentRuntimeTaskScope base) {
        AgentDefinition agent = agentService.get(session.getAgentId());
        AgentTaskCreateRequest request = new AgentTaskCreateRequest();
        request.setTitle("Connector: " + session.getClientName() + " · legacy aggregate");
        request.setDescription(externalDelegationDescription(session, agent)
                + " This legacy client does not provide conversation correlation, so calls are aggregated.");
        request.setAssigneeAgentId(agent.getId());
        request.setCreatedBy(session.getOwnerId());
        request.setOriginType(AgentTaskOriginTypeEnum.CONNECTOR);
        request.setOriginMessageId(session.getId());
        request.setDataScopeSnapshot(effectiveScopes(agent));
        AgentTaskCreation creation = taskService.create(request);
        moveTask(creation.getTask(), AgentTaskStatusEnum.IN_PROGRESS);
        AgentRun run = moveRun(creation.getInitialRun(), AgentRunStatusEnum.RUNNING);

        long revision = session.getRevision();
        session.setTaskId(creation.getTask().getId());
        session.setRunId(run.getId());
        session.setLastUsedAt(new Date());
        session.setRevision(revision + 1);
        storage.updateSession(session, revision);
        base.setTaskId(session.getTaskId());
        base.setRunId(session.getRunId());
        return base;
    }

    private String externalDelegationDescription(AgentConnectorSession session, AgentDefinition agent) {
        return "External Runtime delegation audit. " + session.getClientName()
                + " performs reasoning and tool selection; Chat2DB only enforces data permissions, DataWiki scope,"
                + " approval policy and auditing inherited from Agent " + agent.getName() + ".";
    }

    private void touchConversation(AgentConnectorConversation conversation) {
        long revision = conversation.getRevision();
        conversation.setLastUsedAt(new Date());
        conversation.setRevision(revision + 1);
        storage.updateConversation(conversation, revision);
    }

    private void appendToolEvent(AgentConnectorInvocation invocation, AgentRuntimeEventTypeEnum type,
                                 String content, Map<String, Object> payload) {
        AgentRunEvent event = new AgentRunEvent();
        event.setEventId("connector-" + type.name().toLowerCase() + "-" + invocation.getId());
        event.setRunId(invocation.getRunId());
        event.setType(type);
        event.setContent(content);
        event.setPayload(new LinkedHashMap<>(payload));
        event.setOccurredAt(new Date());
        event.setPersistedAt(new Date());
        runService.appendEvent(event);
    }

    private static String requiredCorrelation(String value, String label, int maxLength) {
        String normalized = StringUtils.trimToNull(value);
        if (normalized == null || normalized.length() > maxLength) {
            throw new IllegalArgumentException(label + " is required and must not exceed " + maxLength + " characters");
        }
        return normalized;
    }

    private static String shortId(String value) {
        return value.length() <= 24 ? value : value.substring(0, 12) + "…" + value.substring(value.length() - 8);
    }

    private static String truncate(String value, int maxLength) {
        String safe = StringUtils.defaultString(value);
        return safe.length() <= maxLength ? safe : safe.substring(0, maxLength) + "…";
    }

    private AgentTask moveTask(AgentTask task, AgentTaskStatusEnum target) {
        AgentTaskTransitionRequest request = new AgentTaskTransitionRequest();
        request.setTaskId(task.getId()); request.setExpectedRevision(task.getRevision()); request.setTargetStatus(target);
        return taskService.transition(request);
    }

    private AgentConnectorPairing requirePairing(String id) {
        AgentConnectorPairing value = storage.getPairing(id);
        if (value == null) throw new NoSuchElementException("Connector pairing not found");
        return value;
    }

    private AgentConnectorSession requireSession(String id) {
        AgentConnectorSession value = storage.getSession(id);
        if (value == null) throw new NoSuchElementException("Connector Session not found");
        return value;
    }

    private AgentConnectorSession requireActiveSession(String id) {
        AgentConnectorSession value = requireSession(id);
        if (value.getStatus() != AgentConnectorSessionStatusEnum.ACTIVE) {
            throw new SecurityException("Connector Session is not active");
        }
        return value;
    }

    private List<ai.chat2db.community.domain.api.model.agent.AgentDataScope> effectiveScopes(AgentDefinition agent) {
        return AgentScopePolicy.copyScopes(agent.getEffectiveDataScopes() == null || agent.getEffectiveDataScopes().isEmpty()
                ? agent.getDataScopes() : agent.getEffectiveDataScopes());
    }

    private boolean visibleTo(AgentDefinition agent, Long ownerId) {
        return agent.getCreatedBy() == null || Objects.equals(agent.getCreatedBy(), ownerId);
    }

    private static void requireToken(String candidate, String expectedHash, String label) {
        if (StringUtils.isBlank(candidate) || !constantEquals(hash(candidate), expectedHash)) {
            throw new SecurityException("invalid " + label);
        }
    }

    private static boolean constantEquals(String left, String right) {
        if (left == null || right == null) return false;
        return MessageDigest.isEqual(left.getBytes(StandardCharsets.UTF_8), right.getBytes(StandardCharsets.UTF_8));
    }

    private static String token() {
        byte[] bytes = new byte[32]; RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String userCode() {
        return "%04d-%04d".formatted(RANDOM.nextInt(10_000), RANDOM.nextInt(10_000));
    }

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}

package ai.chat2db.community.domain.core.impl.agent;

import ai.chat2db.community.domain.api.enums.agent.AgentRunStatusEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentArtifactStatusEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentArtifactTypeEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentRuntimeInstanceStatusEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentRuntimeEventTypeEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentRuntimeLeaseStateEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentRuntimeProviderEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentRuntimeApprovalStatusEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentApprovalDecisionEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentApprovalStatusEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentSqlProposalStatusEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentTaskContextTypeEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentTaskStatusEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentArtifactContentModeEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentToolAttemptStatusEnum;
import ai.chat2db.community.domain.api.model.agent.AgentArtifact;
import ai.chat2db.community.domain.api.model.agent.AgentApproval;
import ai.chat2db.community.domain.api.model.agent.AgentArtifactEvidence;
import ai.chat2db.community.domain.api.model.agent.AgentArtifactVersion;
import ai.chat2db.community.domain.api.model.agent.AgentDataScope;
import ai.chat2db.community.domain.api.model.agent.AgentDefinition;
import ai.chat2db.community.domain.api.model.agent.AgentArtifactDetail;
import ai.chat2db.community.domain.api.model.agent.AgentRun;
import ai.chat2db.community.domain.api.model.agent.AgentRunEvent;
import ai.chat2db.community.domain.api.model.agent.AgentRuntimeInstance;
import ai.chat2db.community.domain.api.model.agent.AgentRuntimeApproval;
import ai.chat2db.community.domain.api.model.agent.AgentRuntimeApprovalResult;
import ai.chat2db.community.domain.api.model.agent.AgentRuntimeArtifactEvidenceRef;
import ai.chat2db.community.domain.api.model.agent.AgentRuntimeArtifactManifest;
import ai.chat2db.community.domain.api.model.agent.AgentRuntimeArtifactResult;
import ai.chat2db.community.domain.api.model.agent.AgentRuntimeEventAccepted;
import ai.chat2db.community.domain.api.model.agent.AgentRuntimeLeaseStatus;
import ai.chat2db.community.domain.api.model.agent.AgentRuntimeMcpEndpoint;
import ai.chat2db.community.domain.api.model.agent.AgentRuntimeProfile;
import ai.chat2db.community.domain.api.model.agent.AgentRuntimeRunClaim;
import ai.chat2db.community.domain.api.model.agent.AgentRuntimeRunLease;
import ai.chat2db.community.domain.api.model.agent.AgentRuntimeRunTerminalResult;
import ai.chat2db.community.domain.api.model.agent.AgentRuntimeTaskScope;
import ai.chat2db.community.domain.api.model.agent.AgentTask;
import ai.chat2db.community.domain.api.model.agent.AgentTaskContext;
import ai.chat2db.community.domain.api.model.agent.AgentSqlProposal;
import ai.chat2db.community.domain.api.model.agent.AgentToolAttempt;
import ai.chat2db.community.domain.api.model.agent.runtime.AgentRuntimeStartRequest;
import ai.chat2db.community.domain.api.model.request.agent.AgentRuntimeLeaseRenewRequest;
import ai.chat2db.community.domain.api.model.request.agent.AgentRuntimeEventRequest;
import ai.chat2db.community.domain.api.model.request.agent.AgentRuntimeRunCancelAckRequest;
import ai.chat2db.community.domain.api.model.request.agent.AgentRuntimeRunClaimRequest;
import ai.chat2db.community.domain.api.model.request.agent.AgentRuntimeRunCompleteRequest;
import ai.chat2db.community.domain.api.model.request.agent.AgentRuntimeRunFailRequest;
import ai.chat2db.community.domain.api.model.request.agent.AgentRuntimeRunStartedRequest;
import ai.chat2db.community.domain.api.model.request.agent.AgentRuntimeRunSuspendRequest;
import ai.chat2db.community.domain.api.model.request.agent.AgentRuntimeApprovalRequest;
import ai.chat2db.community.domain.api.model.request.agent.AgentRuntimeApprovalDecisionRequest;
import ai.chat2db.community.domain.api.model.request.agent.AgentRuntimeApprovalAckRequest;
import ai.chat2db.community.domain.api.model.request.agent.AgentRuntimeArtifactUploadRequest;
import ai.chat2db.community.domain.api.model.request.agent.AgentRuntimeRunTerminalRequest;
import ai.chat2db.community.domain.api.model.request.agent.AgentRunTransitionRequest;
import ai.chat2db.community.domain.api.model.request.agent.AgentArtifactCreateRequest;
import ai.chat2db.community.domain.api.model.request.agent.AgentTaskTransitionRequest;
import ai.chat2db.community.domain.api.service.agent.IAgentContextAssembler;
import ai.chat2db.community.domain.api.service.agent.IAgentArtifactService;
import ai.chat2db.community.domain.api.service.agent.IAgentDefinitionService;
import ai.chat2db.community.domain.api.service.agent.IAgentRunService;
import ai.chat2db.community.domain.api.service.agent.IAgentRuntimeControlService;
import ai.chat2db.community.domain.api.service.agent.IAgentRuntimeDispatchService;
import ai.chat2db.community.domain.api.service.agent.IAgentTaskService;
import ai.chat2db.community.domain.api.service.storage.IAgentControlStorage;
import ai.chat2db.community.domain.api.service.storage.IAgentRuntimeControlStorage;
import com.alibaba.fastjson2.JSON;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.Set;
import java.util.function.Supplier;

@Service
public class AgentRuntimeDispatchServiceImpl implements IAgentRuntimeDispatchService {

    static final long DEFAULT_LEASE_MILLIS = 60_000L;
    private static final int MAX_RESULT_SUMMARY_LENGTH = 20_000;
    private static final int MAX_FINAL_RESPONSE_LENGTH = 1_000_000;
    private static final int MAX_RUNTIME_ARTIFACT_BYTES = 5 * 1024 * 1024;
    private static final Set<String> FILE_MIME_TYPES = Set.of(
            "text/plain", "text/csv", "text/markdown", "application/json", "application/pdf",
            "image/png", "image/jpeg");

    private final IAgentRuntimeControlService runtimeControlService;
    private final IAgentRuntimeControlStorage runtimeStorage;
    private final IAgentControlStorage agentStorage;
    private final IAgentRunService runService;
    private final IAgentTaskService taskService;
    private final IAgentDefinitionService agentService;
    private final IAgentContextAssembler contextAssembler;
    private final IAgentArtifactService artifactService;
    private final Clock clock;
    private final Supplier<String> tokenSupplier;
    private final long leaseMillis;

    @Autowired
    public AgentRuntimeDispatchServiceImpl(IAgentRuntimeControlService runtimeControlService,
                                           IAgentRuntimeControlStorage runtimeStorage,
                                           IAgentControlStorage agentStorage,
                                           IAgentRunService runService,
                                           IAgentTaskService taskService,
                                           IAgentDefinitionService agentService,
                                           IAgentContextAssembler contextAssembler,
                                           IAgentArtifactService artifactService) {
        this(runtimeControlService, runtimeStorage, agentStorage, runService, taskService, agentService,
                contextAssembler, artifactService, Clock.systemUTC(), secureTokenSupplier(), DEFAULT_LEASE_MILLIS);
    }

    AgentRuntimeDispatchServiceImpl(IAgentRuntimeControlService runtimeControlService,
                                    IAgentRuntimeControlStorage runtimeStorage,
                                    IAgentControlStorage agentStorage,
                                    IAgentRunService runService,
                                    IAgentTaskService taskService,
                                    IAgentDefinitionService agentService,
                                    IAgentContextAssembler contextAssembler,
                                    IAgentArtifactService artifactService,
                                    Clock clock, Supplier<String> tokenSupplier, long leaseMillis) {
        this.runtimeControlService = runtimeControlService;
        this.runtimeStorage = runtimeStorage;
        this.agentStorage = agentStorage;
        this.runService = runService;
        this.taskService = taskService;
        this.agentService = agentService;
        this.contextAssembler = contextAssembler;
        this.artifactService = artifactService;
        this.clock = clock;
        this.tokenSupplier = tokenSupplier;
        this.leaseMillis = leaseMillis;
    }

    @Override
    public AgentRuntimeRunClaim claim(String instanceId, AgentRuntimeRunClaimRequest request) {
        AgentRuntimeInstance instance = requireClaimingInstance(instanceId, request);
        String leaseToken = tokenSupplier.get();
        String taskToken = tokenSupplier.get();
        Date now = now();
        Date expiresAt = new Date(now.getTime() + leaseMillis);
        AgentRuntimeRunLease lease = runtimeStorage.claimRuntimeRun(instance.getId(), instance.getProvider(),
                sha256(leaseToken), sha256(taskToken), now, expiresAt);
        if (lease == null) {
            return null;
        }

        AgentRun run = runService.get(lease.getRunId());
        AgentTask task = taskService.get(run.getTaskId());
        AgentDefinition agent = agentService.get(run.getAgentId());
        AgentRuntimeProfile profile = runtimeProfile(run);
        if (profile.getProvider() != instance.getProvider()) {
            throw new IllegalStateException("claimed run provider does not match runtime instance");
        }

        AgentRuntimeStartRequest startRequest = new AgentRuntimeStartRequest();
        startRequest.setRun(run);
        startRequest.setTask(task);
        startRequest.setAgent(agent);
        startRequest.setCurrentInput(currentUserInput(run, task.getId()));
        String assembledContext = contextAssembler.assemble(agent, task, taskService.listRuns(task.getId()));
        if (lease.getLeaseAttempt() > 1) {
            assembledContext += sqlApprovalContinuation(run.getId());
        }
        startRequest.setAssembledContext(assembledContext);

        AgentRuntimeRunClaim claim = new AgentRuntimeRunClaim();
        claim.setRunId(run.getId());
        claim.setTaskId(task.getId());
        claim.setLeaseAttempt(lease.getLeaseAttempt());
        claim.setLeaseRevision(lease.getRevision());
        claim.setLeaseToken(leaseToken);
        claim.setLeaseExpiresAt(lease.getLeaseExpiresAt());
        claim.setTaskScopedToken(taskToken);
        claim.setResumeSessionId(resumeSessionId(run, profile));
        AgentRuntimeMcpEndpoint mcpEndpoint = new AgentRuntimeMcpEndpoint();
        mcpEndpoint.setName("chat2db_task_tools");
        mcpEndpoint.setTransport("STREAMABLE_HTTP");
        mcpEndpoint.setPath("/api/agent/runtime/mcp/runs/" + run.getId());
        mcpEndpoint.setBearerTokenEnvironmentVariable("CHAT2DB_AGENT_TASK_TOKEN");
        claim.setMcpEndpoints(List.of(mcpEndpoint));
        claim.setRuntimeProfile(profile);
        claim.setStartRequest(startRequest);
        return claim;
    }

    @Override
    public AgentRuntimeTaskScope authorizeTaskToken(String runId, String taskToken) {
        if (StringUtils.isBlank(runId) || StringUtils.isBlank(taskToken)) {
            throw new SecurityException("runtime task token and run id are required");
        }
        AgentRuntimeRunLease lease = runtimeStorage.getRuntimeRunLease(runId);
        if (lease == null || lease.getState() != AgentRuntimeLeaseStateEnum.ACTIVE) {
            throw new SecurityException("runtime task token is no longer active");
        }
        if (lease.getLeaseExpiresAt() == null || now().after(lease.getLeaseExpiresAt())) {
            throw new SecurityException("runtime task token has expired");
        }
        if (lease.getStartedAt() == null || StringUtils.isBlank(lease.getRuntimeExecutionId())) {
            throw new SecurityException("runtime task token is unavailable before provider start");
        }
        if (StringUtils.isBlank(lease.getTaskTokenHash())
                || !secureEquals(lease.getTaskTokenHash(), sha256(taskToken))) {
            throw new SecurityException("invalid runtime task token");
        }

        AgentRun run = runService.get(runId);
        if (run.getStatus() != AgentRunStatusEnum.RUNNING
                && run.getStatus() != AgentRunStatusEnum.WAITING_APPROVAL) {
            throw new SecurityException("runtime task token cannot access a non-running run");
        }
        AgentTask task = taskService.get(run.getTaskId());
        AgentRuntimeTaskScope scope = new AgentRuntimeTaskScope();
        scope.setRunId(run.getId());
        scope.setTaskId(task.getId());
        scope.setAgentId(run.getAgentId());
        scope.setTaskOwnerId(task.getCreatedBy());
        scope.setRuntimeInstanceId(lease.getRuntimeInstanceId());
        scope.setLeaseAttempt(lease.getLeaseAttempt());
        scope.setExpiresAt(lease.getLeaseExpiresAt());
        scope.setDataScopes(List.copyOf(task.getDataScopeSnapshot()));
        return scope;
    }

    @Override
    public AgentRuntimeLeaseStatus renewLease(String runId, String leaseToken,
                                               AgentRuntimeLeaseRenewRequest request) {
        AgentRuntimeRunLease current = requireCurrentLease(runId, leaseToken, request, false);
        if (request.getExpectedLeaseRevision() == null || request.getExpectedLeaseRevision() <= 0) {
            throw new IllegalArgumentException("positive expected runtime lease revision is required");
        }
        if (request.getExpectedLeaseRevision() < current.getRevision()) {
            // A renewal may have committed before its HTTP response was lost during a
            // control-plane restart. Return the durable current revision without extending
            // the lease so the owning Daemon can safely resynchronize and retry.
            return status(current);
        }
        requireExpectedLeaseRevision(current, request.getExpectedLeaseRevision());
        AgentRuntimeRunLease updated = copyLease(current);
        Date now = now();
        updated.setLastRenewedAt(now);
        updated.setLeaseExpiresAt(new Date(now.getTime() + leaseMillis));
        updated.setRevision(current.getRevision() + 1);
        return status(runtimeStorage.updateRuntimeRunLease(updated, current.getRevision()));
    }

    @Override
    public AgentRuntimeLeaseStatus markStarted(String runId, String leaseToken,
                                               AgentRuntimeRunStartedRequest request) {
        if (request == null || StringUtils.isBlank(request.getRuntimeExecutionId())) {
            throw new IllegalArgumentException("runtime execution id is required");
        }
        AgentRuntimeRunLease current = requireCurrentLease(runId, leaseToken, request, false);
        AgentRun run = runService.get(runId);
        if (current.getStartedAt() != null) {
            if (!request.getRuntimeExecutionId().trim().equals(current.getRuntimeExecutionId())
                    || run.getStatus() != AgentRunStatusEnum.RUNNING) {
                throw new IllegalStateException("runtime run was already started by another execution");
            }
            // The Run/lease transition is atomic in storage, while the Task transition is a
            // separate domain operation. A repeated ACK repairs that second step if the first
            // attempt committed the Run but lost the response before updating the Task.
            moveTaskToInProgress(run.getTaskId());
            return status(current);
        }
        requireExpectedLeaseRevision(current, request.getExpectedLeaseRevision());
        if (run.getStatus() != AgentRunStatusEnum.DISPATCHED) {
            throw new IllegalStateException("only a dispatched external run can be started");
        }
        Date now = now();
        AgentRuntimeRunLease updated = copyLease(current);
        updated.setStartedAt(now);
        updated.setRuntimeExecutionId(request.getRuntimeExecutionId().trim());
        updated.setLastRenewedAt(now);
        updated.setLeaseExpiresAt(new Date(now.getTime() + leaseMillis));
        updated.setRevision(current.getRevision() + 1);
        AgentRuntimeRunLease started = runtimeStorage.startRuntimeRun(
                updated, current.getRevision(), run.getRevision());
        moveTaskToInProgress(run.getTaskId());
        return status(started);
    }

    @Override
    public AgentRuntimeEventAccepted appendEvent(String runId, String leaseToken, AgentRuntimeEventRequest request) {
        if (request == null || request.getSequence() == null || request.getSequence() <= 0) {
            throw new IllegalArgumentException("positive runtime event sequence is required");
        }
        if (StringUtils.isBlank(request.getEventId())) {
            throw new IllegalArgumentException("runtime event id is required");
        }
        if (request.getEventType() == null) {
            throw new IllegalArgumentException("runtime event type is required");
        }
        if (request.getOccurredAt() == null) {
            throw new IllegalArgumentException("runtime event occurredAt is required");
        }
        AgentRuntimeRunLease lease = requireCurrentLease(runId, leaseToken, request, false);
        AgentRun run = runService.get(runId);
        if (run.getStatus() != AgentRunStatusEnum.RUNNING
                && run.getStatus() != AgentRunStatusEnum.WAITING_APPROVAL) {
            throw new IllegalStateException("runtime events require a running or approval-waiting run");
        }

        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        if (request.getPayload() != null) {
            payload.putAll(request.getPayload());
        }
        payload.put("runtimeEventId", request.getEventId().trim());
        payload.put("runtimeAttempt", lease.getLeaseAttempt());
        payload.put("runtimeSequence", request.getSequence());

        AgentRunEvent event = new AgentRunEvent();
        event.setEventId(externalEventId(lease.getLeaseAttempt(), request.getEventId()));
        event.setRunId(runId);
        event.setRuntimeAttempt(lease.getLeaseAttempt());
        event.setRuntimeSequence(request.getSequence());
        event.setType(request.getEventType());
        event.setContent(request.getContent());
        event.setPayload(payload);
        event.setOccurredAt(request.getOccurredAt());
        event.setPersistedAt(now());
        String providerSessionId = providerSessionId(request);
        if (providerSessionId != null) {
            payload.put("providerSessionId", providerSessionId);
        }
        AgentRunEvent persisted = runtimeStorage.appendRuntimeRunEvent(event, lease.getLeaseAttempt(),
                request.getSequence(), now(), providerSessionId);
        AgentRuntimeEventAccepted accepted = new AgentRuntimeEventAccepted();
        accepted.setEvent(persisted);
        accepted.setLease(status(runtimeStorage.getRuntimeRunLease(runId)));
        return accepted;
    }

    @Override
    public AgentRuntimeArtifactResult uploadArtifact(String runId, String leaseToken,
                                                     AgentRuntimeArtifactUploadRequest request) {
        AgentRuntimeRunLease lease = requireCurrentLease(runId, leaseToken, request, false);
        AgentRun run = runService.get(runId);
        if (run.getStatus() != AgentRunStatusEnum.RUNNING
                && run.getStatus() != AgentRunStatusEnum.WAITING_APPROVAL) {
            throw new IllegalStateException("runtime artifacts require a running or approval-waiting Run");
        }
        AgentRuntimeArtifactManifest manifest = validateArtifactManifest(request == null
                ? null : request.getManifest());
        AgentTask task = taskService.get(run.getTaskId());
        AgentArtifactDetail existing = findRuntimeArtifact(task.getId(), runId, manifest.getType());
        if (existing != null) {
            requireMatchingRuntimeArtifact(existing, manifest);
            return artifactResult(existing.getArtifact(), lease);
        }

        byte[] bytes = artifactBytes(manifest);
        AgentArtifactCreateRequest create = new AgentArtifactCreateRequest();
        create.setTaskId(task.getId());
        create.setType(manifest.getType());
        create.setTitle(manifest.getTitle().trim());
        create.setStatus(AgentArtifactStatusEnum.READY);
        create.setContentMode(AgentArtifactContentModeEnum.SNAPSHOT);
        create.setContent(runtimeArtifactContent(manifest, bytes, lease.getLeaseAttempt()));
        create.setCreatedByRunId(runId);
        create.setCreatedBy(task.getCreatedBy());
        create.setEvidence(runtimeArtifactEvidence(run, task, manifest));
        AgentArtifactDetail created = artifactService.create(create);
        requireMatchingRuntimeArtifact(created, manifest);
        persistArtifactEvent(runId, created);
        return artifactResult(created.getArtifact(), runtimeStorage.getRuntimeRunLease(runId));
    }

    @Override
    public AgentRuntimeApprovalResult requestApproval(String runId, String leaseToken,
                                                      AgentRuntimeApprovalRequest request) {
        validateApprovalRequest(request);
        AgentRuntimeRunLease lease = requireCurrentLease(runId, leaseToken, request, false);
        AgentRun run = runService.get(runId);
        AgentRuntimeProfile profile = runtimeProfile(run);
        if (!Boolean.TRUE.equals(profile.getApprovalBridgeEnabled())) {
            throw new IllegalStateException("runtime approval bridge is disabled for this Run");
        }
        if (run.getStatus() != AgentRunStatusEnum.RUNNING
                && run.getStatus() != AgentRunStatusEnum.WAITING_APPROVAL) {
            throw new IllegalStateException("runtime approval requires a running Run");
        }
        AgentRuntimeApproval approval = new AgentRuntimeApproval();
        approval.setId(UUID.randomUUID().toString());
        approval.setRunId(runId);
        approval.setLeaseAttempt(lease.getLeaseAttempt());
        approval.setProviderRequestId(request.getProviderRequestId().trim());
        approval.setToolCallId(StringUtils.trimToNull(request.getToolCallId()));
        approval.setTitle(request.getTitle().trim());
        approval.setRequestPayload(new LinkedHashMap<>(request.getRequestPayload() == null
                ? Map.of() : request.getRequestPayload()));
        approval.setAllowOptionId(request.getAllowOptionId().trim());
        approval.setRejectOptionId(request.getRejectOptionId().trim());
        approval.setStatus(AgentRuntimeApprovalStatusEnum.PENDING);
        approval.setRequestedAt(now());
        approval.setRevision(1L);
        AgentRuntimeApproval persisted = runtimeStorage.createOrGetRuntimeApproval(approval);
        requireMatchingApprovalRequest(persisted, approval);
        moveRunTo(runId, AgentRunStatusEnum.WAITING_APPROVAL);
        moveTaskTo(run.getTaskId(), AgentTaskStatusEnum.IN_PROGRESS,
                AgentTaskStatusEnum.WAITING_APPROVAL);
        return approvalResult(persisted, runtimeStorage.getRuntimeRunLease(runId));
    }

    @Override
    public AgentRuntimeApprovalResult getApprovalStatus(String runId, String leaseToken,
                                                        AgentRuntimeApprovalAckRequest request) {
        AgentRuntimeRunLease lease = requireCurrentLease(runId, leaseToken, request, false);
        AgentRuntimeApproval approval = requireRuntimeApproval(runId, lease, request, false);
        return approvalResult(approval, lease);
    }

    @Override
    public AgentRuntimeApprovalResult acknowledgeApproval(String runId, String leaseToken,
                                                          AgentRuntimeApprovalAckRequest request) {
        AgentRuntimeRunLease lease = requireCurrentLease(runId, leaseToken, request, false);
        AgentRuntimeApproval approval = requireRuntimeApproval(runId, lease, request, true);
        if (approval.getStatus() != AgentRuntimeApprovalStatusEnum.APPROVED
                && approval.getStatus() != AgentRuntimeApprovalStatusEnum.REJECTED) {
            throw new IllegalStateException("runtime approval has not been decided");
        }
        boolean hasAnotherPendingApproval = runtimeStorage.listRuntimeApprovals(runId).stream()
                .anyMatch(candidate -> candidate.getStatus() == AgentRuntimeApprovalStatusEnum.PENDING);
        if (!hasAnotherPendingApproval) {
            moveRunTo(runId, AgentRunStatusEnum.RUNNING);
            AgentRun run = runService.get(runId);
            moveTaskTo(run.getTaskId(), AgentTaskStatusEnum.WAITING_APPROVAL,
                    AgentTaskStatusEnum.IN_PROGRESS);
        }
        return approvalResult(approval, runtimeStorage.getRuntimeRunLease(runId));
    }

    @Override
    public List<AgentRuntimeApproval> listApprovals(String runId) {
        runService.get(runId);
        return runtimeStorage.listRuntimeApprovals(runId);
    }

    @Override
    public AgentRuntimeApproval getApproval(String approvalId) {
        if (StringUtils.isBlank(approvalId)) {
            throw new IllegalArgumentException("runtime approval id is required");
        }
        AgentRuntimeApproval approval = runtimeStorage.getRuntimeApproval(approvalId);
        if (approval == null) {
            throw new NoSuchElementException("runtime approval not found: " + approvalId);
        }
        return approval;
    }

    @Override
    public AgentRuntimeApproval decideApproval(AgentRuntimeApprovalDecisionRequest request) {
        if (request == null || StringUtils.isBlank(request.getApprovalId())
                || request.getExpectedRevision() == null || request.getExpectedRevision() <= 0
                || request.getDecision() == null || request.getDecidedBy() == null) {
            throw new IllegalArgumentException("runtime approval decision is incomplete");
        }
        AgentRuntimeApproval current = runtimeStorage.getRuntimeApproval(request.getApprovalId());
        if (current == null) {
            throw new NoSuchElementException("runtime approval not found: " + request.getApprovalId());
        }
        if (!current.getRevision().equals(request.getExpectedRevision())) {
            throw new ConcurrentModificationException("runtime approval revision has changed: " + current.getId());
        }
        if (current.getStatus() != AgentRuntimeApprovalStatusEnum.PENDING) {
            throw new IllegalStateException("runtime approval is no longer pending");
        }
        AgentRun run = runService.get(current.getRunId());
        if (run.getStatus() != AgentRunStatusEnum.WAITING_APPROVAL) {
            throw new IllegalStateException("runtime Run is not waiting for approval");
        }
        AgentRuntimeApproval updated = copyApproval(current);
        updated.setDecision(request.getDecision());
        updated.setStatus(request.getDecision() == AgentApprovalDecisionEnum.APPROVE
                ? AgentRuntimeApprovalStatusEnum.APPROVED : AgentRuntimeApprovalStatusEnum.REJECTED);
        updated.setDecidedBy(request.getDecidedBy());
        updated.setDecidedAt(now());
        updated.setReason(StringUtils.trimToNull(request.getReason()));
        updated.setRevision(current.getRevision() + 1);
        return runtimeStorage.updateRuntimeApproval(updated, current.getRevision());
    }

    private String providerSessionId(AgentRuntimeEventRequest request) {
        if (request.getEventType() != AgentRuntimeEventTypeEnum.SESSION_UPDATED) {
            return null;
        }
        Object raw = request.getPayload() == null ? null : request.getPayload().get("sessionId");
        if (!(raw instanceof String) || StringUtils.isBlank((String) raw)) {
            raw = request.getPayload() == null ? null : request.getPayload().get("threadId");
        }
        String sessionId = raw instanceof String ? StringUtils.trimToNull((String) raw) : null;
        if (sessionId == null || sessionId.length() > 512) {
            throw new IllegalArgumentException("SESSION_UPDATED requires a provider session id of at most 512 characters");
        }
        return sessionId;
    }

    private String resumeSessionId(AgentRun run, AgentRuntimeProfile profile) {
        if (!Boolean.TRUE.equals(profile.getSessionResumeEnabled())) {
            return null;
        }
        if (StringUtils.isNotBlank(run.getProviderSessionId())) {
            return run.getProviderSessionId();
        }
        if (StringUtils.isBlank(run.getParentRunId())) {
            return null;
        }
        AgentRun parent = runService.get(run.getParentRunId());
        if (!Objects.equals(run.getAgentId(), parent.getAgentId())
                || run.getRuntimeProvider() != parent.getRuntimeProvider()
                || !Objects.equals(run.getRuntimeProfileId(), parent.getRuntimeProfileId())
                || !Objects.equals(run.getRuntimeProfileSnapshot(), parent.getRuntimeProfileSnapshot())) {
            return null;
        }
        return StringUtils.trimToNull(parent.getProviderSessionId());
    }

    @Override
    public AgentRuntimeRunTerminalResult complete(String runId, String leaseToken,
                                                  AgentRuntimeRunCompleteRequest request) {
        String finalResponse = StringUtils.trimToNull(request == null ? null : request.getFinalResponse());
        if (finalResponse != null && finalResponse.length() > MAX_FINAL_RESPONSE_LENGTH) {
            throw new IllegalArgumentException("runtime final response is too large");
        }
        AgentRuntimeRunTerminalResult result = finish(runId, leaseToken, request,
                AgentRunStatusEnum.COMPLETED, null, completedAnswer(runId, finalResponse));
        finalizeCompletedRun(runId, null);
        return result;
    }

    @Override
    public AgentRuntimeRunTerminalResult fail(String runId, String leaseToken,
                                              AgentRuntimeRunFailRequest request) {
        if (request == null || StringUtils.isBlank(request.getFailureReason())) {
            throw new IllegalArgumentException("runtime failure reason is required");
        }
        AgentRunStatusEnum target = Boolean.TRUE.equals(request.getOutcomeUnknown())
                ? AgentRunStatusEnum.UNKNOWN : AgentRunStatusEnum.FAILED;
        return finish(runId, leaseToken, request, target, request.getFailureReason().trim(), null);
    }

    @Override
    public AgentRuntimeRunTerminalResult acknowledgeCancellation(String runId, String leaseToken,
                                                                  AgentRuntimeRunCancelAckRequest request) {
        AgentRuntimeRunLease lease = requireLeaseIdentity(runId, leaseToken, request);
        if (lease.getState() == AgentRuntimeLeaseStateEnum.ACTIVE && lease.getCancelRequestedAt() == null) {
            throw new IllegalStateException("runtime cancellation was not requested");
        }
        return finish(runId, leaseToken, request, AgentRunStatusEnum.CANCELLED, null, null);
    }

    @Override
    public AgentRuntimeLeaseStatus suspendForSqlApproval(String runId, String leaseToken,
                                                         AgentRuntimeRunSuspendRequest request) {
        validateTerminalRequest(request);
        AgentRuntimeRunLease lease = requireLeaseIdentity(runId, leaseToken, request);
        if (lease.getState() != AgentRuntimeLeaseStateEnum.ACTIVE) {
            if (lease.getState() != AgentRuntimeLeaseStateEnum.SUSPENDED
                    || !externalEventId(lease.getLeaseAttempt(), request.getEventId())
                    .equals(lease.getTerminalEventId())) {
                throw new IllegalStateException("runtime run already has a different lease outcome: " + runId);
            }
            return status(lease);
        }
        if (now().after(lease.getLeaseExpiresAt())) {
            throw new IllegalStateException("runtime run lease has expired");
        }
        requireExpectedLeaseRevision(lease, request.getExpectedLeaseRevision());
        AgentRun run = runService.get(runId);
        if (run.getStatus() != AgentRunStatusEnum.WAITING_APPROVAL || !hasSqlContinuation(runId)) {
            throw new IllegalStateException("runtime run has no resumable SQL approval continuation");
        }
        AgentRunStatusEnum target = hasDecidedSqlContinuation(runId)
                ? AgentRunStatusEnum.QUEUED : AgentRunStatusEnum.WAITING_APPROVAL;
        AgentRunEvent event = new AgentRunEvent();
        event.setEventId(externalEventId(lease.getLeaseAttempt(), request.getEventId()));
        event.setRunId(runId);
        event.setRuntimeAttempt(lease.getLeaseAttempt());
        event.setRuntimeSequence(request.getSequence());
        event.setType(AgentRuntimeEventTypeEnum.STATUS);
        event.setContent(target.name());
        event.setPayload(Map.of(
                "status", target.name(),
                "reason", "SQL_APPROVAL_CONTINUATION_SUSPENDED",
                "runtimeAttempt", lease.getLeaseAttempt()));
        event.setOccurredAt(request.getOccurredAt());
        event.setPersistedAt(now());
        AgentRuntimeRunLease suspended = runtimeStorage.suspendRuntimeRun(
                lease, event, target, now(), request.getExpectedLeaseRevision(), run.getRevision());
        if (target == AgentRunStatusEnum.QUEUED) {
            moveTaskTo(run.getTaskId(), AgentTaskStatusEnum.WAITING_APPROVAL,
                    AgentTaskStatusEnum.IN_PROGRESS);
        }
        return status(suspended);
    }

    @Override
    public AgentRun requestCancellation(String runId) {
        AgentRun run = runService.get(runId);
        if (terminal(run.getStatus())) {
            return run;
        }
        if (run.getRuntimeType() != ai.chat2db.community.domain.api.enums.agent.AgentRuntimeTypeEnum.EXTERNAL_AGENT) {
            throw new IllegalArgumentException("runtime cancellation request is only for external runs");
        }
        if (run.getStatus() == AgentRunStatusEnum.QUEUED) {
            AgentRunTransitionRequest transition = new AgentRunTransitionRequest();
            transition.setRunId(runId);
            transition.setExpectedRevision(run.getRevision());
            transition.setTargetStatus(AgentRunStatusEnum.CANCELLED);
            AgentRun cancelled = runService.transition(transition);
            syncTerminalTask(cancelled);
            return cancelled;
        }
        AgentRuntimeRunLease lease = runtimeStorage.requestRuntimeRunCancellation(runId, now());
        if (lease == null || lease.getState() != AgentRuntimeLeaseStateEnum.ACTIVE) {
            throw new IllegalStateException("active runtime run lease not found: " + runId);
        }
        return runService.get(runId);
    }

    @Override
    public int reconcileExpiredLeases(int limit) {
        List<String> reconciledRunIds = runtimeStorage.reconcileExpiredRuntimeRuns(now(), limit);
        reconciledRunIds.forEach(runId -> {
            AgentRun run = runService.get(runId);
            if (run.getStatus() == AgentRunStatusEnum.QUEUED) {
                moveTaskTo(run.getTaskId(), AgentTaskStatusEnum.WAITING_APPROVAL,
                        AgentTaskStatusEnum.IN_PROGRESS);
            } else {
                syncTerminalTask(run);
            }
        });
        return reconciledRunIds.size();
    }

    private AgentRuntimeRunTerminalResult finish(String runId, String leaseToken,
                                                 AgentRuntimeRunTerminalRequest request,
                                                 AgentRunStatusEnum targetStatus, String failureReason,
                                                 String resultSummary) {
        validateTerminalRequest(request);
        AgentRuntimeRunLease lease = requireLeaseIdentity(runId, leaseToken, request);
        String eventId = externalEventId(lease.getLeaseAttempt(), request.getEventId());
        if (lease.getState() != AgentRuntimeLeaseStateEnum.ACTIVE) {
            requireMatchingTerminalAcknowledgement(lease, targetStatus, eventId);
            AgentRun terminalRun = runService.get(runId);
            syncTerminalTask(terminalRun);
            return terminalResult(terminalRun, lease);
        }
        if (now().after(lease.getLeaseExpiresAt())) {
            throw new IllegalStateException("runtime run lease has expired");
        }
        requireExpectedLeaseRevision(lease, request.getExpectedLeaseRevision());
        AgentRun run = runService.get(runId);
        requireTerminalSourceStatus(run, targetStatus);

        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        payload.put("runtimeEventId", request.getEventId().trim());
        payload.put("runtimeAttempt", lease.getLeaseAttempt());
        payload.put("runtimeSequence", request.getSequence());
        payload.put("status", targetStatus.name());
        if (targetStatus == AgentRunStatusEnum.COMPLETED && StringUtils.isNotBlank(resultSummary)) {
            payload.put("finalResponse", resultSummary);
        }
        if (StringUtils.isNotBlank(failureReason)) {
            payload.put("failureReason", failureReason);
        }

        AgentRunEvent event = new AgentRunEvent();
        event.setEventId(eventId);
        event.setRunId(runId);
        event.setRuntimeAttempt(lease.getLeaseAttempt());
        event.setRuntimeSequence(request.getSequence());
        event.setType(AgentRuntimeEventTypeEnum.STATUS);
        event.setContent(targetStatus.name());
        event.setPayload(payload);
        event.setOccurredAt(request.getOccurredAt());
        event.setPersistedAt(now());

        AgentRuntimeRunLease finished = runtimeStorage.finishRuntimeRun(
                lease, event, targetStatus, failureReason, truncateSummary(resultSummary), now(),
                request.getExpectedLeaseRevision(), run.getRevision());
        AgentRun terminalRun = runService.get(runId);
        syncTerminalTask(terminalRun);
        return terminalResult(terminalRun, finished);
    }

    private void validateTerminalRequest(AgentRuntimeRunTerminalRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("runtime terminal request is required");
        }
        if (StringUtils.isBlank(request.getEventId())) {
            throw new IllegalArgumentException("runtime terminal event id is required");
        }
        if (request.getSequence() == null || request.getSequence() <= 0) {
            throw new IllegalArgumentException("positive runtime terminal sequence is required");
        }
        if (request.getOccurredAt() == null) {
            throw new IllegalArgumentException("runtime terminal occurredAt is required");
        }
    }

    private void requireTerminalSourceStatus(AgentRun run, AgentRunStatusEnum targetStatus) {
        if (targetStatus == AgentRunStatusEnum.COMPLETED && run.getStatus() != AgentRunStatusEnum.RUNNING) {
            throw new IllegalStateException("only a running external run can complete");
        }
        if (targetStatus != AgentRunStatusEnum.COMPLETED
                && run.getStatus() != AgentRunStatusEnum.DISPATCHED
                && run.getStatus() != AgentRunStatusEnum.RUNNING
                && run.getStatus() != AgentRunStatusEnum.WAITING_APPROVAL) {
            throw new IllegalStateException("external run cannot enter terminal status from " + run.getStatus());
        }
    }

    private void requireMatchingTerminalAcknowledgement(AgentRuntimeRunLease lease,
                                                         AgentRunStatusEnum targetStatus,
                                                         String eventId) {
        AgentRuntimeLeaseStateEnum expectedState = switch (targetStatus) {
            case COMPLETED -> AgentRuntimeLeaseStateEnum.COMPLETED;
            case FAILED -> AgentRuntimeLeaseStateEnum.FAILED;
            case CANCELLED -> AgentRuntimeLeaseStateEnum.CANCELLED;
            case UNKNOWN -> AgentRuntimeLeaseStateEnum.UNKNOWN;
            default -> throw new IllegalArgumentException("run status is not terminal: " + targetStatus);
        };
        if (lease.getState() != expectedState || !eventId.equals(lease.getTerminalEventId())) {
            throw new IllegalStateException("runtime run already has a different terminal acknowledgement: "
                    + lease.getRunId());
        }
    }

    private AgentRuntimeRunTerminalResult terminalResult(AgentRun run, AgentRuntimeRunLease lease) {
        AgentRuntimeRunTerminalResult result = new AgentRuntimeRunTerminalResult();
        result.setRunId(run.getId());
        result.setRunStatus(run.getStatus());
        result.setLeaseAttempt(lease.getLeaseAttempt());
        result.setLeaseRevision(lease.getRevision());
        result.setLeaseState(lease.getState());
        result.setReleasedAt(lease.getReleasedAt());
        return result;
    }

    private void finalizeCompletedRun(String runId, String preferredResponse) {
        AgentRun run = runService.get(runId);
        if (run.getStatus() != AgentRunStatusEnum.COMPLETED) {
            return;
        }
        AgentTask task = taskService.get(run.getTaskId());
        AgentDefinition definition = agentService.get(run.getAgentId());
        String markdown = completedAnswer(runId, preferredResponse);
        if (StringUtils.isNotBlank(markdown)) {
            AgentArtifactCreateRequest artifact = new AgentArtifactCreateRequest();
            artifact.setTaskId(task.getId());
            artifact.setType(AgentArtifactTypeEnum.REPORT);
            artifact.setTitle(task.getTitle() + " - Analysis Report");
            artifact.setStatus(AgentArtifactStatusEnum.READY);
            artifact.setCreatedByRunId(runId);
            artifact.setCreatedBy(task.getCreatedBy());
            artifact.setContent(Map.of(
                    "artifactType", AgentArtifactTypeEnum.REPORT.name(),
                    "blocks", List.of(Map.of("type", "MARKDOWN", "content", markdown))));
            AgentArtifactDetail created = artifactService.create(artifact);
            persistArtifactEvent(runId, created);
            for (AgentArtifactDetail extracted : artifactService.extractStructuredArtifacts(
                    task.getId(), runId, task.getCreatedBy(), markdown)) {
                persistArtifactEvent(runId, extracted);
            }
        }
        AgentTask refreshed = taskService.get(task.getId());
        if (refreshed.getStatus() == AgentTaskStatusEnum.IN_PROGRESS
                && artifactService.satisfiesOutputContract(definition, task.getId())) {
            AgentTaskTransitionRequest transition = new AgentTaskTransitionRequest();
            transition.setTaskId(task.getId());
            transition.setExpectedRevision(refreshed.getRevision());
            transition.setTargetStatus(AgentTaskStatusEnum.IN_REVIEW);
            taskService.transition(transition);
        }
    }

    private void persistArtifactEvent(String runId, AgentArtifactDetail detail) {
        AgentRunEvent event = new AgentRunEvent();
        event.setEventId("artifact-created-" + detail.getArtifact().getId());
        event.setRunId(runId);
        event.setType(AgentRuntimeEventTypeEnum.ARTIFACT_CREATED);
        event.setContent(detail.getArtifact().getTitle());
        event.setPayload(Map.of(
                "artifactId", detail.getArtifact().getId(),
                "artifactType", detail.getArtifact().getType().name(),
                "version", detail.getArtifact().getCurrentVersion()));
        event.setOccurredAt(now());
        event.setPersistedAt(now());
        agentStorage.appendRunEvent(event);
    }

    private AgentRuntimeArtifactManifest validateArtifactManifest(AgentRuntimeArtifactManifest manifest) {
        if (manifest == null || StringUtils.isBlank(manifest.getArtifactId())) {
            throw new IllegalArgumentException("runtime artifact manifest and artifact id are required");
        }
        if (!manifest.getArtifactId().matches("[A-Za-z0-9._-]{1,128}")) {
            throw new IllegalArgumentException("runtime artifact id contains unsupported characters");
        }
        if (manifest.getType() == null || StringUtils.isBlank(manifest.getTitle())
                || manifest.getTitle().trim().length() > 256) {
            throw new IllegalArgumentException("runtime artifact type and a title up to 256 characters are required");
        }
        if (StringUtils.isBlank(manifest.getMimeType()) || manifest.getSize() == null
                || manifest.getSize() < 0 || manifest.getSize() > MAX_RUNTIME_ARTIFACT_BYTES
                || StringUtils.isBlank(manifest.getSha256())
                || !manifest.getSha256().matches("(?i)[0-9a-f]{64}")) {
            throw new IllegalArgumentException("runtime artifact MIME, size and SHA-256 are invalid");
        }
        String expectedMime = switch (manifest.getType()) {
            case REPORT -> "text/markdown";
            case CHART, DATA_TABLE, METRIC -> "application/json";
            case FILE -> null;
        };
        if (expectedMime != null && !expectedMime.equalsIgnoreCase(manifest.getMimeType())) {
            throw new IllegalArgumentException("runtime artifact MIME does not match its type");
        }
        if (manifest.getType() == AgentArtifactTypeEnum.FILE) {
            if (!FILE_MIME_TYPES.contains(manifest.getMimeType().toLowerCase())
                    || StringUtils.isBlank(manifest.getContentBase64())
                    || manifest.getContentBase64().length() > ((MAX_RUNTIME_ARTIFACT_BYTES + 2) / 3) * 4
                    || StringUtils.isNotBlank(manifest.getContent())) {
                throw new IllegalArgumentException("runtime file artifact content or MIME is unsupported");
            }
            manifest.setFileName(sanitizeFileName(manifest.getFileName()));
        } else if (manifest.getContent() == null || manifest.getContent().length() > MAX_RUNTIME_ARTIFACT_BYTES
                || StringUtils.isNotBlank(manifest.getContentBase64())) {
            throw new IllegalArgumentException("runtime structured artifact requires inline text content");
        }
        byte[] bytes = artifactBytes(manifest);
        if (bytes.length != manifest.getSize()
                || !sha256(bytes).equalsIgnoreCase(manifest.getSha256())) {
            throw new IllegalArgumentException("runtime artifact size or SHA-256 does not match its content");
        }
        return manifest;
    }

    private byte[] artifactBytes(AgentRuntimeArtifactManifest manifest) {
        if (manifest.getType() != AgentArtifactTypeEnum.FILE) {
            return manifest.getContent().getBytes(StandardCharsets.UTF_8);
        }
        try {
            return Base64.getDecoder().decode(manifest.getContentBase64());
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("runtime file artifact is not valid Base64", exception);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> runtimeArtifactContent(AgentRuntimeArtifactManifest manifest, byte[] bytes,
                                                       int leaseAttempt) {
        LinkedHashMap<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("clientArtifactId", manifest.getArtifactId());
        metadata.put("mimeType", manifest.getMimeType().toLowerCase());
        metadata.put("size", bytes.length);
        metadata.put("sha256", sha256(bytes));
        metadata.put("leaseAttempt", leaseAttempt);
        LinkedHashMap<String, Object> content = new LinkedHashMap<>();
        content.put("artifactType", manifest.getType().name());
        content.put("runtimeManifest", metadata);
        switch (manifest.getType()) {
            case REPORT -> content.put("blocks", List.of(
                    Map.of("type", "MARKDOWN", "content", manifest.getContent())));
            case CHART, DATA_TABLE, METRIC -> {
                Object parsed;
                try {
                    parsed = JSON.parse(manifest.getContent());
                } catch (RuntimeException exception) {
                    throw new IllegalArgumentException("runtime artifact JSON content is invalid", exception);
                }
                if (!(parsed instanceof Map<?, ?> map) || map.isEmpty()) {
                    throw new IllegalArgumentException("runtime artifact JSON content must be a non-empty object");
                }
                map.forEach((key, value) -> content.put(String.valueOf(key), value));
                content.put("artifactType", manifest.getType().name());
                content.put("runtimeManifest", metadata);
            }
            case FILE -> {
                content.put("fileName", manifest.getFileName());
                content.put("mimeType", manifest.getMimeType().toLowerCase());
                content.put("size", bytes.length);
                content.put("sha256", sha256(bytes));
                content.put("encoding", "base64");
                content.put("data", Base64.getEncoder().encodeToString(bytes));
            }
        }
        return content;
    }

    private List<AgentArtifactEvidence> runtimeArtifactEvidence(AgentRun run, AgentTask task,
                                                                AgentRuntimeArtifactManifest manifest) {
        List<AgentArtifactEvidence> evidence = new java.util.ArrayList<>();
        Set<String> seen = new java.util.HashSet<>();
        for (AgentRuntimeArtifactEvidenceRef reference : manifest.getEvidence() == null
                ? List.<AgentRuntimeArtifactEvidenceRef>of() : manifest.getEvidence()) {
            if (reference == null || StringUtils.isBlank(reference.getToolAttemptId())
                    || !seen.add(reference.getToolAttemptId().trim())) {
                throw new IllegalArgumentException("runtime artifact evidence contains an invalid tool attempt");
            }
            AgentToolAttempt attempt = agentStorage.getToolAttempt(reference.getToolAttemptId().trim());
            if (attempt == null || !run.getId().equals(attempt.getRunId())
                    || attempt.getStatus() != AgentToolAttemptStatusEnum.SUCCEEDED) {
                throw new SecurityException("runtime artifact evidence is not a successful attempt of this Run");
            }
            AgentSqlProposal proposal = agentStorage.getSqlProposal(attempt.getProposalId());
            if (proposal == null || !evidenceWithinTaskScope(task, proposal)) {
                throw new SecurityException("runtime artifact evidence is outside the Task DataScope");
            }
            AgentArtifactEvidence item = new AgentArtifactEvidence();
            item.setRunId(run.getId());
            item.setToolAttemptId(attempt.getId());
            item.setDataSourceId(proposal.getDataSourceId());
            item.setDatabaseName(proposal.getDatabaseName());
            item.setSchemaName(proposal.getSchemaName());
            item.setSqlSnapshot(proposal.getSqlSnapshot());
            item.setSqlHash(proposal.getSqlHash());
            item.setExecutedAt(attempt.getCompletedAt());
            item.setResultSnapshotId(attempt.getId());
            evidence.add(item);
        }
        return evidence;
    }

    private boolean evidenceWithinTaskScope(AgentTask task, AgentSqlProposal proposal) {
        return task.getDataScopeSnapshot() != null && task.getDataScopeSnapshot().stream().anyMatch(scope ->
                Objects.equals(scope.getDataSourceId(), proposal.getDataSourceId())
                        && scopeMatches(scope.getDatabaseName(), proposal.getDatabaseName())
                        && scopeMatches(scope.getSchemaName(), proposal.getSchemaName()));
    }

    private boolean scopeMatches(String allowed, String actual) {
        return StringUtils.isBlank(allowed) || Objects.equals(allowed, actual);
    }

    private String sanitizeFileName(String fileName) {
        String value = StringUtils.trimToNull(fileName);
        if (value == null || value.length() > 255 || value.contains("/") || value.contains("\\")
                || value.contains("..") || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("runtime artifact file name is unsafe");
        }
        String sanitized = value.replaceAll("[^\\p{L}\\p{N}._-]", "_")
                .replaceAll("^[.]+|[.]+$", "");
        if (sanitized.isBlank()) {
            throw new IllegalArgumentException("runtime artifact file name is empty after sanitization");
        }
        return sanitized;
    }

    private AgentArtifactDetail findRuntimeArtifact(String taskId, String runId, AgentArtifactTypeEnum type) {
        return artifactService.listByTask(taskId).stream()
                .filter(artifact -> runId.equals(artifact.getCreatedByRunId()) && artifact.getType() == type)
                .findFirst().map(artifact -> artifactService.get(artifact.getId())).orElse(null);
    }

    private void requireMatchingRuntimeArtifact(AgentArtifactDetail detail,
                                                AgentRuntimeArtifactManifest manifest) {
        AgentArtifact artifact = detail.getArtifact();
        AgentArtifactVersion current = detail.getVersions().stream()
                .filter(version -> Objects.equals(version.getVersion(), artifact.getCurrentVersion()))
                .findFirst().orElseThrow(() -> new IllegalStateException("runtime artifact version is missing"));
        Object value = current.getContent().get("runtimeManifest");
        if (!(value instanceof Map<?, ?> metadata)
                || !Objects.equals(manifest.getArtifactId(), metadata.get("clientArtifactId"))
                || !manifest.getSha256().equalsIgnoreCase(String.valueOf(metadata.get("sha256")))) {
            throw new IllegalStateException("runtime reused an artifact type with different manifest content");
        }
    }

    private AgentRuntimeArtifactResult artifactResult(AgentArtifact artifact,
                                                       AgentRuntimeRunLease lease) {
        AgentRuntimeArtifactResult result = new AgentRuntimeArtifactResult();
        result.setArtifact(artifact);
        result.setLease(status(lease));
        return result;
    }

    private String completedAnswer(String runId, String preferredResponse) {
        if (StringUtils.isNotBlank(preferredResponse)) {
            return preferredResponse.trim();
        }
        List<AgentRunEvent> events = agentStorage.listRunEvents(runId);
        String terminalResponse = events.stream()
                .filter(event -> event.getType() == AgentRuntimeEventTypeEnum.STATUS)
                .filter(event -> event.getPayload() != null
                        && AgentRunStatusEnum.COMPLETED.name().equals(event.getPayload().get("status")))
                .map(event -> event.getPayload().get("finalResponse"))
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .filter(StringUtils::isNotBlank)
                .reduce((first, second) -> second)
                .orElse(null);
        if (terminalResponse != null) {
            return terminalResponse;
        }
        return events.stream()
                .filter(event -> event.getType() == AgentRuntimeEventTypeEnum.MESSAGE_DELTA)
                .map(AgentRunEvent::getContent)
                .filter(StringUtils::isNotEmpty)
                .reduce("", String::concat);
    }

    private String truncateSummary(String value) {
        if (StringUtils.isBlank(value)) {
            return null;
        }
        return value.length() <= MAX_RESULT_SUMMARY_LENGTH
                ? value : value.substring(0, MAX_RESULT_SUMMARY_LENGTH) + "\n[truncated]";
    }

    private String externalEventId(int leaseAttempt, String runtimeEventId) {
        return "external-" + leaseAttempt + "-" + sha256(runtimeEventId.trim());
    }

    private boolean terminal(AgentRunStatusEnum status) {
        return status == AgentRunStatusEnum.COMPLETED || status == AgentRunStatusEnum.FAILED
                || status == AgentRunStatusEnum.CANCELLED || status == AgentRunStatusEnum.UNKNOWN;
    }

    private AgentRuntimeInstance requireClaimingInstance(String instanceId, AgentRuntimeRunClaimRequest request) {
        if (request == null || StringUtils.isBlank(request.getDaemonId())) {
            throw new IllegalArgumentException("runtime claim daemon id is required");
        }
        AgentRuntimeInstance instance = runtimeControlService.getInstance(instanceId);
        if (!instance.getDaemonId().equals(request.getDaemonId().trim())) {
            throw new IllegalArgumentException("runtime daemon id does not match registered instance");
        }
        if (instance.getProvider() == AgentRuntimeProviderEnum.SPRING_AI) {
            throw new IllegalArgumentException("embedded Spring AI cannot claim external runs");
        }
        if (instance.getStatus() != AgentRuntimeInstanceStatusEnum.ONLINE
                && instance.getStatus() != AgentRuntimeInstanceStatusEnum.DEGRADED) {
            throw new IllegalStateException("runtime instance is not available: " + instance.getStatus());
        }
        if (instance.getActiveRuns() >= instance.getMaxConcurrency()) {
            throw new IllegalStateException("runtime instance has no available execution slot");
        }
        return instance;
    }

    private AgentRuntimeRunLease requireCurrentLease(String runId, String leaseToken,
                                                      AgentRuntimeLeaseRenewRequest request,
                                                      boolean requireExpectedRevision) {
        AgentRuntimeRunLease lease = requireLeaseIdentity(runId, leaseToken, request);
        if (lease.getState() != AgentRuntimeLeaseStateEnum.ACTIVE) {
            throw new IllegalStateException("runtime run lease is no longer active: " + runId);
        }
        if (now().after(lease.getLeaseExpiresAt())) {
            throw new IllegalStateException("runtime run lease has expired");
        }
        if (requireExpectedRevision) {
            requireExpectedLeaseRevision(lease, request.getExpectedLeaseRevision());
        }
        return lease;
    }

    private AgentRuntimeRunLease requireLeaseIdentity(String runId, String leaseToken,
                                                       AgentRuntimeLeaseRenewRequest request) {
        if (request == null || StringUtils.isBlank(request.getDaemonId())) {
            throw new IllegalArgumentException("runtime lease request and daemon id are required");
        }
        if (request.getLeaseAttempt() == null || request.getLeaseAttempt() <= 0) {
            throw new IllegalArgumentException("positive runtime lease attempt is required");
        }
        if (StringUtils.isBlank(leaseToken)) {
            throw new SecurityException("runtime lease token is required");
        }
        AgentRuntimeRunLease lease = runtimeStorage.getRuntimeRunLease(runId);
        if (lease == null) {
            throw new IllegalStateException("runtime run lease not found: " + runId);
        }
        AgentRuntimeInstance instance = runtimeControlService.getInstance(lease.getRuntimeInstanceId());
        if (!instance.getDaemonId().equals(request.getDaemonId().trim())) {
            throw new SecurityException("runtime daemon does not own this run lease");
        }
        if (!lease.getLeaseAttempt().equals(request.getLeaseAttempt())) {
            throw new SecurityException("stale runtime run lease attempt");
        }
        if (!secureEquals(lease.getLeaseTokenHash(), sha256(leaseToken))) {
            throw new SecurityException("invalid runtime run lease token");
        }
        return lease;
    }

    private void requireExpectedLeaseRevision(AgentRuntimeRunLease lease, Long expectedRevision) {
        if (expectedRevision == null || expectedRevision <= 0) {
            throw new IllegalArgumentException("positive expected runtime lease revision is required");
        }
        if (!lease.getRevision().equals(expectedRevision)) {
            throw new ConcurrentModificationException("runtime run lease revision has changed: " + lease.getRunId());
        }
    }

    private AgentRuntimeProfile runtimeProfile(AgentRun run) {
        if (StringUtils.isNotBlank(run.getRuntimeProfileSnapshot())
                && run.getRuntimeProfileSnapshot().trim().startsWith("{")) {
            try {
                AgentRuntimeProfile profile = JSON.parseObject(
                        run.getRuntimeProfileSnapshot(), AgentRuntimeProfile.class);
                if (profile != null) {
                    return profile;
                }
            } catch (RuntimeException exception) {
                throw new IllegalStateException("runtime profile snapshot is invalid for run " + run.getId(), exception);
            }
        }
        AgentRuntimeProfile profile = runtimeStorage.getRuntimeProfile(run.getRuntimeProfileId());
        if (profile == null) {
            throw new IllegalStateException("runtime profile is unavailable for run " + run.getId());
        }
        return profile;
    }

    private String currentUserInput(AgentRun run, String taskId) {
        if (run.getTriggerType() != ai.chat2db.community.domain.api.enums.agent.AgentRunTriggerTypeEnum.USER_MESSAGE) {
            return null;
        }
        List<AgentTaskContext> contexts = agentStorage.listTaskContexts(taskId);
        for (int index = contexts.size() - 1; index >= 0; index--) {
            AgentTaskContext context = contexts.get(index);
            if (context.getType() == AgentTaskContextTypeEnum.COMMENT
                    && StringUtils.isNotBlank(context.getContent())) {
                return context.getContent();
            }
        }
        return null;
    }

    private void moveTaskToInProgress(String taskId) {
        AgentTask task = taskService.get(taskId);
        if (task.getStatus() != AgentTaskStatusEnum.TODO) {
            return;
        }
        AgentTaskTransitionRequest transition = new AgentTaskTransitionRequest();
        transition.setTaskId(taskId);
        transition.setExpectedRevision(task.getRevision());
        transition.setTargetStatus(AgentTaskStatusEnum.IN_PROGRESS);
        taskService.transition(transition);
    }

    private void validateApprovalRequest(AgentRuntimeApprovalRequest request) {
        if (request == null || StringUtils.isBlank(request.getProviderRequestId())
                || request.getProviderRequestId().trim().length() > 256
                || StringUtils.isBlank(request.getTitle()) || request.getTitle().trim().length() > 512
                || StringUtils.isBlank(request.getAllowOptionId())
                || StringUtils.isBlank(request.getRejectOptionId())
                || request.getAllowOptionId().trim().length() > 256
                || request.getRejectOptionId().trim().length() > 256
                || request.getAllowOptionId().trim().equals(request.getRejectOptionId().trim())) {
            throw new IllegalArgumentException("runtime approval request is incomplete or invalid");
        }
        if (StringUtils.length(request.getToolCallId()) > 256) {
            throw new IllegalArgumentException("runtime approval tool call id is too long");
        }
        String payload = JSON.toJSONString(request.getRequestPayload() == null
                ? Map.of() : request.getRequestPayload());
        if (payload.length() > 100_000) {
            throw new IllegalArgumentException("runtime approval payload is too large");
        }
    }

    private AgentRuntimeApproval requireRuntimeApproval(String runId, AgentRuntimeRunLease lease,
                                                        AgentRuntimeApprovalAckRequest request,
                                                        boolean requireCurrentRevision) {
        if (request == null || StringUtils.isBlank(request.getApprovalId())
                || request.getExpectedApprovalRevision() == null
                || request.getExpectedApprovalRevision() <= 0) {
            throw new IllegalArgumentException("runtime approval identity and revision are required");
        }
        AgentRuntimeApproval approval = runtimeStorage.getRuntimeApproval(request.getApprovalId());
        if (approval == null || !runId.equals(approval.getRunId())) {
            throw new NoSuchElementException("runtime approval not found for Run: " + request.getApprovalId());
        }
        if (!lease.getLeaseAttempt().equals(approval.getLeaseAttempt())) {
            throw new SecurityException("runtime approval belongs to a stale lease attempt");
        }
        if (request.getExpectedApprovalRevision() > approval.getRevision()
                || (requireCurrentRevision
                && !approval.getRevision().equals(request.getExpectedApprovalRevision()))) {
            throw new ConcurrentModificationException("runtime approval revision has changed: " + approval.getId());
        }
        return approval;
    }

    private void requireMatchingApprovalRequest(AgentRuntimeApproval current, AgentRuntimeApproval requested) {
        if (!Objects.equals(current.getRunId(), requested.getRunId())
                || !Objects.equals(current.getLeaseAttempt(), requested.getLeaseAttempt())
                || !Objects.equals(current.getProviderRequestId(), requested.getProviderRequestId())
                || !Objects.equals(current.getToolCallId(), requested.getToolCallId())
                || !Objects.equals(current.getTitle(), requested.getTitle())
                || !Objects.equals(current.getRequestPayload(), requested.getRequestPayload())
                || !Objects.equals(current.getAllowOptionId(), requested.getAllowOptionId())
                || !Objects.equals(current.getRejectOptionId(), requested.getRejectOptionId())) {
            throw new IllegalStateException("provider reused a runtime approval request id with different content");
        }
    }

    private void moveRunTo(String runId, AgentRunStatusEnum target) {
        AgentRun run = runService.get(runId);
        if (run.getStatus() == target) {
            return;
        }
        AgentRunTransitionRequest transition = new AgentRunTransitionRequest();
        transition.setRunId(runId);
        transition.setExpectedRevision(run.getRevision());
        transition.setTargetStatus(target);
        runService.transition(transition);
    }

    private void moveTaskTo(String taskId, AgentTaskStatusEnum expected, AgentTaskStatusEnum target) {
        AgentTask task = taskService.get(taskId);
        if (task.getStatus() != expected) {
            return;
        }
        AgentTaskTransitionRequest transition = new AgentTaskTransitionRequest();
        transition.setTaskId(taskId);
        transition.setExpectedRevision(task.getRevision());
        transition.setTargetStatus(target);
        taskService.transition(transition);
    }

    private AgentRuntimeApprovalResult approvalResult(AgentRuntimeApproval approval,
                                                      AgentRuntimeRunLease lease) {
        AgentRuntimeApprovalResult result = new AgentRuntimeApprovalResult();
        result.setApproval(approval);
        result.setLease(status(lease));
        return result;
    }

    private AgentRuntimeApproval copyApproval(AgentRuntimeApproval source) {
        AgentRuntimeApproval copy = new AgentRuntimeApproval();
        copy.setId(source.getId());
        copy.setRunId(source.getRunId());
        copy.setLeaseAttempt(source.getLeaseAttempt());
        copy.setProviderRequestId(source.getProviderRequestId());
        copy.setToolCallId(source.getToolCallId());
        copy.setTitle(source.getTitle());
        copy.setRequestPayload(new LinkedHashMap<>(source.getRequestPayload()));
        copy.setAllowOptionId(source.getAllowOptionId());
        copy.setRejectOptionId(source.getRejectOptionId());
        copy.setStatus(source.getStatus());
        copy.setRequestedAt(source.getRequestedAt());
        copy.setDecidedBy(source.getDecidedBy());
        copy.setDecidedAt(source.getDecidedAt());
        copy.setDecision(source.getDecision());
        copy.setReason(source.getReason());
        copy.setRevision(source.getRevision());
        return copy;
    }

    private AgentRuntimeLeaseStatus status(AgentRuntimeRunLease lease) {
        AgentRuntimeLeaseStatus status = new AgentRuntimeLeaseStatus();
        status.setRunId(lease.getRunId());
        status.setLeaseAttempt(lease.getLeaseAttempt());
        status.setLeaseRevision(lease.getRevision());
        status.setLeaseExpiresAt(lease.getLeaseExpiresAt());
        status.setCancelRequested(lease.getCancelRequestedAt() != null);
        AgentRun run = runService.get(lease.getRunId());
        status.setRunStatus(run.getStatus());
        ApprovalContinuationState continuation = run.getStatus() == AgentRunStatusEnum.WAITING_APPROVAL
                ? approvalContinuationState(run.getId()) : new ApprovalContinuationState(false, false);
        status.setApprovalDecisionPending(continuation.approvalDecisionPending());
        status.setSqlContinuationAvailable(continuation.sqlContinuationAvailable());
        status.setState(lease.getState());
        status.setReleasedAt(lease.getReleasedAt());
        return status;
    }

    private ApprovalContinuationState approvalContinuationState(String runId) {
        List<AgentSqlProposal> activeProposals = agentStorage.listSqlProposals(runId).stream()
                .filter(proposal -> proposal.getStatus() == AgentSqlProposalStatusEnum.ACTIVE)
                .toList();
        Set<String> activeProposalIds = activeProposals.stream()
                .map(AgentSqlProposal::getId).collect(java.util.stream.Collectors.toSet());
        List<AgentApproval> sqlApprovals = agentStorage.listApprovals(runId).stream()
                .filter(approval -> activeProposalIds.contains(approval.getProposalId()))
                .toList();
        boolean sqlDecisionPending = sqlApprovals.stream()
                .anyMatch(approval -> approval.getStatus() == AgentApprovalStatusEnum.PENDING);
        boolean sqlContinuationAvailable = sqlApprovals.stream().anyMatch(approval ->
                approval.getStatus() == AgentApprovalStatusEnum.PENDING
                        || approval.getStatus() == AgentApprovalStatusEnum.APPROVED
                        || approval.getStatus() == AgentApprovalStatusEnum.REJECTED);
        boolean runtimeDecisionPending = runtimeStorage.listRuntimeApprovals(runId).stream()
                .anyMatch(approval -> approval.getStatus() == AgentRuntimeApprovalStatusEnum.PENDING);
        return new ApprovalContinuationState(
                sqlDecisionPending || runtimeDecisionPending, sqlContinuationAvailable);
    }

    private boolean hasSqlContinuation(String runId) {
        return approvalContinuationState(runId).sqlContinuationAvailable();
    }

    private boolean hasDecidedSqlContinuation(String runId) {
        Set<String> activeProposalIds = agentStorage.listSqlProposals(runId).stream()
                .filter(proposal -> proposal.getStatus() == AgentSqlProposalStatusEnum.ACTIVE)
                .map(AgentSqlProposal::getId).collect(java.util.stream.Collectors.toSet());
        return agentStorage.listApprovals(runId).stream().anyMatch(approval ->
                activeProposalIds.contains(approval.getProposalId())
                        && (approval.getStatus() == AgentApprovalStatusEnum.APPROVED
                        || approval.getStatus() == AgentApprovalStatusEnum.REJECTED));
    }

    private String sqlApprovalContinuation(String runId) {
        Map<String, AgentSqlProposal> proposals = agentStorage.listSqlProposals(runId).stream()
                .filter(proposal -> proposal.getStatus() == AgentSqlProposalStatusEnum.ACTIVE)
                .collect(java.util.stream.Collectors.toMap(AgentSqlProposal::getId, proposal -> proposal));
        List<AgentApproval> decisions = agentStorage.listApprovals(runId).stream()
                .filter(approval -> approval.getStatus() == AgentApprovalStatusEnum.APPROVED
                        || approval.getStatus() == AgentApprovalStatusEnum.REJECTED)
                .filter(approval -> proposals.containsKey(approval.getProposalId()))
                .toList();
        if (decisions.isEmpty()) {
            return "";
        }
        StringBuilder context = new StringBuilder("\n\n## SQL Approval Continuation\n")
                .append("Continue this same Run using the user's persisted SQL approval decision.\n");
        for (AgentApproval decision : decisions) {
            AgentSqlProposal proposal = proposals.get(decision.getProposalId());
            context.append("- decision=").append(decision.getStatus());
            context.append(", datasourceId=").append(proposal.getDataSourceId());
            if (StringUtils.isNotBlank(proposal.getDatabaseName())) {
                context.append(", database=").append(proposal.getDatabaseName());
            }
            if (StringUtils.isNotBlank(proposal.getSchemaName())) {
                context.append(", schema=").append(proposal.getSchemaName());
            }
            context.append("\n```sql\n").append(proposal.getSqlSnapshot()).append("\n```\n");
            if (decision.getStatus() == AgentApprovalStatusEnum.APPROVED) {
                context.append("Call execute_sql with this exact SQL and target. Chat2DB will execute it ")
                        .append("idempotently or replay its saved result; do not request approval again.\n");
            } else {
                context.append("The user rejected this SQL. Do not execute it; explain the outcome or choose a safe alternative.\n");
            }
        }
        return context.toString();
    }

    private record ApprovalContinuationState(boolean approvalDecisionPending,
                                             boolean sqlContinuationAvailable) {
    }

    private void syncTerminalTask(AgentRun run) {
        if (run == null) {
            return;
        }
        AgentTaskStatusEnum target = switch (run.getStatus()) {
            case FAILED, UNKNOWN -> AgentTaskStatusEnum.BLOCKED;
            case CANCELLED -> AgentTaskStatusEnum.CANCELLED;
            default -> null;
        };
        if (target == null) {
            return;
        }
        for (int attempt = 0; attempt < 2; attempt++) {
            AgentTask task = taskService.get(run.getTaskId());
            if (task.getStatus() == target || task.getStatus() == AgentTaskStatusEnum.DONE
                    || task.getStatus() == AgentTaskStatusEnum.CANCELLED) {
                return;
            }
            if (target == AgentTaskStatusEnum.BLOCKED
                    && task.getStatus() != AgentTaskStatusEnum.TODO
                    && task.getStatus() != AgentTaskStatusEnum.IN_PROGRESS
                    && task.getStatus() != AgentTaskStatusEnum.WAITING_APPROVAL
                    && task.getStatus() != AgentTaskStatusEnum.IN_REVIEW) {
                return;
            }
            AgentTaskTransitionRequest transition = new AgentTaskTransitionRequest();
            transition.setTaskId(task.getId());
            transition.setExpectedRevision(task.getRevision());
            transition.setTargetStatus(target);
            try {
                taskService.transition(transition);
                return;
            } catch (ConcurrentModificationException conflict) {
                if (attempt == 1) {
                    throw conflict;
                }
            }
        }
    }

    private AgentRuntimeRunLease copyLease(AgentRuntimeRunLease source) {
        AgentRuntimeRunLease copy = new AgentRuntimeRunLease();
        copy.setRunId(source.getRunId());
        copy.setRuntimeInstanceId(source.getRuntimeInstanceId());
        copy.setLeaseAttempt(source.getLeaseAttempt());
        copy.setLeaseTokenHash(source.getLeaseTokenHash());
        copy.setTaskTokenHash(source.getTaskTokenHash());
        copy.setClaimedAt(source.getClaimedAt());
        copy.setLeaseExpiresAt(source.getLeaseExpiresAt());
        copy.setLastRenewedAt(source.getLastRenewedAt());
        copy.setStartedAt(source.getStartedAt());
        copy.setRuntimeExecutionId(source.getRuntimeExecutionId());
        copy.setCancelRequestedAt(source.getCancelRequestedAt());
        copy.setLastEventSequence(source.getLastEventSequence());
        copy.setState(source.getState());
        copy.setReleasedAt(source.getReleasedAt());
        copy.setTerminalEventId(source.getTerminalEventId());
        copy.setRevision(source.getRevision());
        return copy;
    }

    private Date now() {
        return Date.from(clock.instant());
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private String sha256(byte[] value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private boolean secureEquals(String expected, String actual) {
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
    }

    private static Supplier<String> secureTokenSupplier() {
        SecureRandom random = new SecureRandom();
        return () -> {
            byte[] bytes = new byte[32];
            random.nextBytes(bytes);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        };
    }
}

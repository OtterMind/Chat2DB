package ai.chat2db.community.domain.core.impl.agent;

import ai.chat2db.community.domain.api.enums.agent.AgentRunStatusEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentRunTriggerTypeEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentRuntimeInstanceStatusEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentRuntimeLeaseStateEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentRuntimeEventTypeEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentRuntimeProviderEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentRuntimeTransportEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentRuntimeTypeEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentTaskStatusEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentApprovalDecisionEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentRuntimeApprovalStatusEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentArtifactTypeEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentToolAttemptStatusEnum;
import ai.chat2db.community.domain.api.model.agent.AgentDefinition;
import ai.chat2db.community.domain.api.model.agent.AgentArtifact;
import ai.chat2db.community.domain.api.model.agent.AgentArtifactDetail;
import ai.chat2db.community.domain.api.model.agent.AgentRun;
import ai.chat2db.community.domain.api.model.agent.AgentRuntimeInstance;
import ai.chat2db.community.domain.api.model.agent.AgentRuntimeEventAccepted;
import ai.chat2db.community.domain.api.model.agent.AgentRuntimeProfile;
import ai.chat2db.community.domain.api.model.agent.AgentRuntimeRunClaim;
import ai.chat2db.community.domain.api.model.agent.AgentRuntimeRunLease;
import ai.chat2db.community.domain.api.model.agent.AgentRuntimeTaskScope;
import ai.chat2db.community.domain.api.model.agent.AgentRuntimeApproval;
import ai.chat2db.community.domain.api.model.agent.AgentRuntimeApprovalResult;
import ai.chat2db.community.domain.api.model.agent.AgentRuntimeArtifactManifest;
import ai.chat2db.community.domain.api.model.agent.AgentRuntimeArtifactResult;
import ai.chat2db.community.domain.api.model.agent.AgentArtifactVersion;
import ai.chat2db.community.domain.api.model.agent.AgentRuntimeArtifactEvidenceRef;
import ai.chat2db.community.domain.api.model.agent.AgentDataScope;
import ai.chat2db.community.domain.api.model.agent.AgentSqlProposal;
import ai.chat2db.community.domain.api.model.agent.AgentToolAttempt;
import ai.chat2db.community.domain.api.model.agent.AgentRunEvent;
import ai.chat2db.community.domain.api.model.agent.AgentTask;
import ai.chat2db.community.domain.api.model.request.agent.AgentRuntimeLeaseRenewRequest;
import ai.chat2db.community.domain.api.model.request.agent.AgentRuntimeEventRequest;
import ai.chat2db.community.domain.api.model.request.agent.AgentRuntimeRunClaimRequest;
import ai.chat2db.community.domain.api.model.request.agent.AgentRuntimeRunStartedRequest;
import ai.chat2db.community.domain.api.model.request.agent.AgentRuntimeRunCompleteRequest;
import ai.chat2db.community.domain.api.model.request.agent.AgentRuntimeRunCancelAckRequest;
import ai.chat2db.community.domain.api.model.request.agent.AgentRuntimeRunFailRequest;
import ai.chat2db.community.domain.api.model.request.agent.AgentRunTransitionRequest;
import ai.chat2db.community.domain.api.model.request.agent.AgentRuntimeApprovalRequest;
import ai.chat2db.community.domain.api.model.request.agent.AgentRuntimeApprovalDecisionRequest;
import ai.chat2db.community.domain.api.model.request.agent.AgentRuntimeApprovalAckRequest;
import ai.chat2db.community.domain.api.model.request.agent.AgentRuntimeArtifactUploadRequest;
import ai.chat2db.community.domain.api.model.request.agent.AgentArtifactCreateRequest;
import ai.chat2db.community.domain.api.model.request.agent.AgentTaskTransitionRequest;
import ai.chat2db.community.domain.api.service.agent.IAgentContextAssembler;
import ai.chat2db.community.domain.api.service.agent.IAgentArtifactService;
import ai.chat2db.community.domain.api.service.agent.IAgentDefinitionService;
import ai.chat2db.community.domain.api.service.agent.IAgentRunService;
import ai.chat2db.community.domain.api.service.agent.IAgentRuntimeControlService;
import ai.chat2db.community.domain.api.service.agent.IAgentTaskService;
import ai.chat2db.community.domain.api.service.storage.IAgentControlStorage;
import ai.chat2db.community.domain.api.service.storage.IAgentRuntimeControlStorage;
import com.alibaba.fastjson2.JSON;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ConcurrentModificationException;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentRuntimeDispatchServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-15T08:00:00Z");

    private AgentRun run;
    private AgentRun parentRun;
    private AgentTask task;
    private AgentDefinition agent;
    private AgentRuntimeProfile profile;
    private AgentRuntimeInstance instance;
    private MemoryRuntimeStorage runtimeStorage;
    private AtomicBoolean reportCreated;
    private AtomicReference<String> reportMarkdown;
    private Map<AgentArtifactTypeEnum, AgentArtifactDetail> artifacts;
    private AgentToolAttempt evidenceAttempt;
    private AgentSqlProposal evidenceProposal;
    private AtomicReference<AgentArtifactCreateRequest> artifactRequest;

    @BeforeEach
    void setUp() {
        profile = new AgentRuntimeProfile();
        profile.setId("profile-1");
        profile.setName("Codex local");
        profile.setTransport(AgentRuntimeTransportEnum.EXTERNAL_DAEMON);
        profile.setProvider(AgentRuntimeProviderEnum.CODEX);
        profile.setSessionResumeEnabled(true);
        profile.setApprovalBridgeEnabled(true);
        profile.setEnabled(true);
        profile.setCreatedBy(7L);
        profile.setRevision(1L);

        instance = new AgentRuntimeInstance();
        instance.setId("instance-1");
        instance.setDaemonId("daemon-1");
        instance.setProvider(AgentRuntimeProviderEnum.CODEX);
        instance.setStatus(AgentRuntimeInstanceStatusEnum.ONLINE);
        instance.setMaxConcurrency(1);
        instance.setActiveRuns(0);

        run = new AgentRun();
        run.setId("run-1");
        run.setTaskId("task-1");
        run.setAgentId("agent-1");
        run.setRuntimeType(AgentRuntimeTypeEnum.EXTERNAL_AGENT);
        run.setRuntimeProfileId(profile.getId());
        run.setRuntimeProvider(profile.getProvider());
        run.setRuntimeProfileSnapshot(JSON.toJSONString(profile));
        run.setTriggerType(AgentRunTriggerTypeEnum.TASK_CREATED);
        run.setStatus(AgentRunStatusEnum.DISPATCHED);
        run.setRevision(2L);

        task = new AgentTask();
        task.setId("task-1");
        task.setCreatedBy(7L);
        task.setStatus(AgentTaskStatusEnum.TODO);
        task.setRevision(1L);

        agent = new AgentDefinition();
        agent.setId("agent-1");

        runtimeStorage = new MemoryRuntimeStorage(run);
        reportCreated = new AtomicBoolean();
        reportMarkdown = new AtomicReference<>();
        artifacts = new LinkedHashMap<>();
        artifactRequest = new AtomicReference<>();
        AgentDataScope scope = new AgentDataScope();
        scope.setDataSourceId(7L);
        scope.setDatabaseName("sales");
        scope.setSchemaName("public");
        task.setDataScopeSnapshot(List.of(scope));
    }

    @Test
    void resumesCompatibleParentProviderSessionWhenProfileAllowsIt() {
        parentRun = new AgentRun();
        parentRun.setId("run-parent");
        parentRun.setAgentId(run.getAgentId());
        parentRun.setRuntimeProvider(run.getRuntimeProvider());
        parentRun.setRuntimeProfileId(run.getRuntimeProfileId());
        parentRun.setRuntimeProfileSnapshot(run.getRuntimeProfileSnapshot());
        parentRun.setProviderSessionId("codex-thread-parent");
        run.setParentRunId(parentRun.getId());

        AgentRuntimeRunClaim claim = serviceAt(NOW, "lease-token", "task-token")
                .claim(instance.getId(), claimRequest());

        assertEquals("codex-thread-parent", claim.getResumeSessionId());
    }

    @Test
    void claimsWithOpaqueTokensRenewsAndStartsWithAttemptFencing() {
        AgentRuntimeDispatchServiceImpl service = serviceAt(NOW, "lease-token", "task-token");
        AgentRuntimeRunClaimRequest claimRequest = new AgentRuntimeRunClaimRequest();
        claimRequest.setDaemonId(instance.getDaemonId());

        AgentRuntimeRunClaim claim = service.claim(instance.getId(), claimRequest);

        assertEquals(run.getId(), claim.getRunId());
        assertEquals("lease-token", claim.getLeaseToken());
        assertEquals("task-token", claim.getTaskScopedToken());
        assertEquals(1, claim.getMcpEndpoints().size());
        assertEquals("chat2db_task_tools", claim.getMcpEndpoints().get(0).getName());
        assertEquals("STREAMABLE_HTTP", claim.getMcpEndpoints().get(0).getTransport());
        assertEquals("/api/agent/runtime/mcp/runs/" + run.getId(),
                claim.getMcpEndpoints().get(0).getPath());
        assertEquals("CHAT2DB_AGENT_TASK_TOKEN",
                claim.getMcpEndpoints().get(0).getBearerTokenEnvironmentVariable());
        assertEquals("assembled-context", claim.getStartRequest().getAssembledContext());
        assertNull(claim.getResumeSessionId());
        assertEquals(profile.getId(), claim.getRuntimeProfile().getId());
        assertNotEquals(claim.getLeaseToken(), runtimeStorage.lease.getLeaseTokenHash());
        assertNotEquals(claim.getTaskScopedToken(), runtimeStorage.lease.getTaskTokenHash());
        assertEquals(64, runtimeStorage.lease.getLeaseTokenHash().length());
        assertEquals(64, runtimeStorage.lease.getTaskTokenHash().length());
        assertThrows(SecurityException.class,
                () -> service.authorizeTaskToken(run.getId(), claim.getTaskScopedToken()));

        AgentRuntimeLeaseRenewRequest wrongToken = renewRequest(1, 1L);
        assertThrows(SecurityException.class,
                () -> service.renewLease(run.getId(), "wrong-token", wrongToken));

        assertEquals(2L, service.renewLease(run.getId(), claim.getLeaseToken(), wrongToken)
                .getLeaseRevision());
        assertEquals(2L, service.renewLease(run.getId(), claim.getLeaseToken(), wrongToken)
                .getLeaseRevision());
        assertEquals(2L, runtimeStorage.lease.getRevision());

        AgentRuntimeRunStartedRequest startedRequest = new AgentRuntimeRunStartedRequest();
        startedRequest.setDaemonId(instance.getDaemonId());
        startedRequest.setLeaseAttempt(1);
        startedRequest.setExpectedLeaseRevision(2L);
        startedRequest.setRuntimeExecutionId("codex-thread-1");
        assertEquals(3L, service.markStarted(run.getId(), claim.getLeaseToken(), startedRequest)
                .getLeaseRevision());
        assertEquals(AgentRunStatusEnum.RUNNING, run.getStatus());
        assertEquals(AgentTaskStatusEnum.IN_PROGRESS, task.getStatus());
        assertNotNull(runtimeStorage.lease.getStartedAt());

        AgentRuntimeTaskScope taskScope = service.authorizeTaskToken(
                run.getId(), claim.getTaskScopedToken());
        assertEquals(run.getId(), taskScope.getRunId());
        assertEquals(task.getId(), taskScope.getTaskId());
        assertEquals(agent.getId(), taskScope.getAgentId());
        assertEquals(7L, taskScope.getTaskOwnerId());
        assertEquals(1, taskScope.getLeaseAttempt());
        assertThrows(SecurityException.class,
                () -> service.authorizeTaskToken(run.getId(), "wrong-task-token"));

        AgentRuntimeEventRequest firstEvent = eventRequest(1L, "message-1");
        firstEvent.setEventType(AgentRuntimeEventTypeEnum.SESSION_UPDATED);
        firstEvent.setPayload(Map.of("threadId", "codex-thread-1"));
        AgentRuntimeEventAccepted persisted = service.appendEvent(run.getId(), claim.getLeaseToken(), firstEvent);
        AgentRuntimeEventAccepted duplicate = service.appendEvent(run.getId(), claim.getLeaseToken(), firstEvent);
        assertEquals(persisted.getEvent().getSequence(), duplicate.getEvent().getSequence());
        assertEquals(1L, persisted.getEvent().getRuntimeSequence());
        assertEquals("message-1", persisted.getEvent().getPayload().get("runtimeEventId"));
        assertEquals("codex-thread-1", persisted.getEvent().getPayload().get("providerSessionId"));
        assertEquals("codex-thread-1", run.getProviderSessionId());
        assertEquals(4L, duplicate.getLease().getLeaseRevision());
        assertThrows(IllegalStateException.class,
                () -> service.appendEvent(run.getId(), claim.getLeaseToken(), eventRequest(3L, "message-3")));

        // Repeated ACK is idempotent and also repairs the non-atomic Task transition when needed.
        task.setStatus(AgentTaskStatusEnum.TODO);
        service.markStarted(run.getId(), claim.getLeaseToken(), startedRequest);
        assertEquals(AgentTaskStatusEnum.IN_PROGRESS, task.getStatus());

        AgentRuntimeLeaseRenewRequest staleAttempt = renewRequest(2, 3L);
        assertThrows(SecurityException.class,
                () -> service.renewLease(run.getId(), claim.getLeaseToken(), staleAttempt));
    }

    @Test
    void fakeRuntimeCompletesClaimStartToolEventAndTerminalLifecycle() {
        AgentRuntimeDispatchServiceImpl service = serviceAt(NOW, "fake-lease", "fake-task");

        AgentRuntimeRunClaim claim = service.claim(instance.getId(), claimRequest());
        assertEquals(AgentRunStatusEnum.DISPATCHED, run.getStatus());

        service.markStarted(run.getId(), claim.getLeaseToken(), startedRequest(1L));
        assertEquals(AgentRunStatusEnum.RUNNING, run.getStatus());
        assertEquals(run.getId(), service.authorizeTaskToken(
                run.getId(), claim.getTaskScopedToken()).getRunId());

        AgentRuntimeEventAccepted progress = service.appendEvent(
                run.getId(), claim.getLeaseToken(), eventRequest(1L, "fake-progress"));
        assertEquals(1L, progress.getEvent().getRuntimeSequence());

        AgentRuntimeRunCompleteRequest complete = terminalRequest(
                new AgentRuntimeRunCompleteRequest(), 2L, "fake-complete");
        complete.setExpectedLeaseRevision(runtimeStorage.lease.getRevision());
        complete.setFinalResponse("# Fake Runtime Result\n\nLifecycle completed.");
        assertEquals(AgentRunStatusEnum.COMPLETED,
                service.complete(run.getId(), claim.getLeaseToken(), complete).getRunStatus());
        assertEquals(AgentTaskStatusEnum.IN_REVIEW, task.getStatus());
        assertEquals(AgentRuntimeLeaseStateEnum.COMPLETED, runtimeStorage.lease.getState());
        assertEquals(0, instance.getActiveRuns());
        assertThrows(SecurityException.class, () -> service.authorizeTaskToken(
                run.getId(), claim.getTaskScopedToken()));
    }

    @Test
    void rejectsRenewalAfterLeaseExpiry() {
        AgentRuntimeDispatchServiceImpl service = serviceAt(NOW, "lease-token", "task-token");
        AgentRuntimeRunClaimRequest claimRequest = new AgentRuntimeRunClaimRequest();
        claimRequest.setDaemonId(instance.getDaemonId());
        AgentRuntimeRunClaim claim = service.claim(instance.getId(), claimRequest);

        AgentRuntimeDispatchServiceImpl expiredService = serviceAt(
                NOW.plusMillis(AgentRuntimeDispatchServiceImpl.DEFAULT_LEASE_MILLIS + 1),
                "unused-lease-token", "unused-task-token");

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> expiredService.renewLease(run.getId(), claim.getLeaseToken(), renewRequest(1, 1L)));
        assertFalse(exception.getMessage().isBlank());
        assertThrows(SecurityException.class,
                () -> expiredService.authorizeTaskToken(run.getId(), claim.getTaskScopedToken()));
    }

    @Test
    void completesIdempotentlyCreatesReportAndMovesTaskToReview() {
        AgentRuntimeDispatchServiceImpl service = serviceAt(NOW, "lease-token", "task-token");
        AgentRuntimeRunClaim claim = service.claim(instance.getId(), claimRequest());
        service.markStarted(run.getId(), claim.getLeaseToken(), startedRequest(1L));

        AgentRuntimeRunCompleteRequest complete = terminalRequest(new AgentRuntimeRunCompleteRequest(), 1L,
                "terminal-complete");
        complete.setExpectedLeaseRevision(2L);
        complete.setFinalResponse("# Analysis\n\nEverything is complete.");

        assertEquals(AgentRunStatusEnum.COMPLETED,
                service.complete(run.getId(), claim.getLeaseToken(), complete).getRunStatus());
        assertEquals(AgentRuntimeLeaseStateEnum.COMPLETED, runtimeStorage.lease.getState());
        assertEquals(AgentTaskStatusEnum.IN_REVIEW, task.getStatus());
        assertEquals("# Analysis\n\nEverything is complete.", run.getResultSummary());
        assertEquals(0, instance.getActiveRuns());
        assertTrue(reportCreated.get());
        assertThrows(SecurityException.class,
                () -> service.authorizeTaskToken(run.getId(), claim.getTaskScopedToken()));

        complete.setFinalResponse("tampered retry content");
        assertEquals(AgentRunStatusEnum.COMPLETED,
                service.complete(run.getId(), claim.getLeaseToken(), complete).getRunStatus());
        assertEquals("# Analysis\n\nEverything is complete.", reportMarkdown.get());
        assertThrows(IllegalStateException.class,
                () -> service.renewLease(run.getId(), claim.getLeaseToken(), renewRequest(1, 3L)));
    }

    @Test
    void requestsCancellationBeforeAcceptingIdempotentDaemonAck() {
        AgentRuntimeDispatchServiceImpl service = serviceAt(NOW, "lease-token", "task-token");
        AgentRuntimeRunClaim claim = service.claim(instance.getId(), claimRequest());
        service.markStarted(run.getId(), claim.getLeaseToken(), startedRequest(1L));

        AgentRuntimeRunCancelAckRequest premature = terminalRequest(
                new AgentRuntimeRunCancelAckRequest(), 1L, "terminal-cancel");
        premature.setExpectedLeaseRevision(2L);
        assertThrows(IllegalStateException.class,
                () -> service.acknowledgeCancellation(run.getId(), claim.getLeaseToken(), premature));

        assertEquals(AgentRunStatusEnum.RUNNING, service.requestCancellation(run.getId()).getStatus());
        assertNotNull(runtimeStorage.lease.getCancelRequestedAt());
        AgentRuntimeRunCancelAckRequest acknowledgement = terminalRequest(
                new AgentRuntimeRunCancelAckRequest(), 1L, "terminal-cancel");
        acknowledgement.setExpectedLeaseRevision(3L);

        assertEquals(AgentRunStatusEnum.CANCELLED,
                service.acknowledgeCancellation(run.getId(), claim.getLeaseToken(), acknowledgement)
                        .getRunStatus());
        assertEquals(AgentRuntimeLeaseStateEnum.CANCELLED, runtimeStorage.lease.getState());
        assertEquals(0, instance.getActiveRuns());
        assertEquals(AgentRunStatusEnum.CANCELLED,
                service.acknowledgeCancellation(run.getId(), claim.getLeaseToken(), acknowledgement)
                        .getRunStatus());
    }

    @Test
    void cancelsUnclaimedExternalRunWithoutCreatingLease() {
        run.setStatus(AgentRunStatusEnum.QUEUED);
        run.setRevision(1L);
        AgentRuntimeDispatchServiceImpl service = serviceAt(NOW, "unused-token");

        assertEquals(AgentRunStatusEnum.CANCELLED, service.requestCancellation(run.getId()).getStatus());
        assertEquals(2L, run.getRevision());
        assertNull(runtimeStorage.lease);
    }

    @Test
    void reportsProviderFailureAndReleasesRuntimeSlot() {
        AgentRuntimeDispatchServiceImpl service = serviceAt(NOW, "lease-token", "task-token");
        AgentRuntimeRunClaim claim = service.claim(instance.getId(), claimRequest());
        service.markStarted(run.getId(), claim.getLeaseToken(), startedRequest(1L));
        AgentRuntimeRunFailRequest failure = terminalRequest(
                new AgentRuntimeRunFailRequest(), 1L, "terminal-failure");
        failure.setExpectedLeaseRevision(2L);
        failure.setFailureReason("provider process exited with code 17");

        assertEquals(AgentRunStatusEnum.FAILED,
                service.fail(run.getId(), claim.getLeaseToken(), failure).getRunStatus());
        assertEquals(AgentRuntimeLeaseStateEnum.FAILED, runtimeStorage.lease.getState());
        assertEquals("provider process exited with code 17", run.getFailureReason());
        assertEquals(0, instance.getActiveRuns());
        assertFalse(reportCreated.get());
    }

    @Test
    void persistsRuntimeApprovalWaitsForUserAndResumesOnlyAfterDaemonAck() {
        AgentRuntimeDispatchServiceImpl service = serviceAt(NOW, "lease-token", "task-token");
        AgentRuntimeRunClaim claim = service.claim(instance.getId(), claimRequest());
        service.markStarted(run.getId(), claim.getLeaseToken(), startedRequest(1L));

        AgentRuntimeApprovalRequest approvalRequest = new AgentRuntimeApprovalRequest();
        approvalRequest.setDaemonId(instance.getDaemonId());
        approvalRequest.setLeaseAttempt(1);
        approvalRequest.setExpectedLeaseRevision(runtimeStorage.lease.getRevision());
        approvalRequest.setProviderRequestId("acp-request-91");
        approvalRequest.setToolCallId("tool-1");
        approvalRequest.setTitle("Write report file");
        approvalRequest.setRequestPayload(Map.of("path", "report.md"));
        approvalRequest.setAllowOptionId("allow_once");
        approvalRequest.setRejectOptionId("deny_once");

        AgentRuntimeApprovalResult pending = service.requestApproval(
                run.getId(), claim.getLeaseToken(), approvalRequest);
        assertEquals(AgentRuntimeApprovalStatusEnum.PENDING, pending.getApproval().getStatus());
        assertEquals(AgentRunStatusEnum.WAITING_APPROVAL, run.getStatus());
        assertEquals(pending.getApproval().getId(), service.requestApproval(
                run.getId(), claim.getLeaseToken(), approvalRequest).getApproval().getId());

        AgentRuntimeApprovalDecisionRequest decision = new AgentRuntimeApprovalDecisionRequest();
        decision.setApprovalId(pending.getApproval().getId());
        decision.setExpectedRevision(1L);
        decision.setDecision(AgentApprovalDecisionEnum.APPROVE);
        decision.setDecidedBy(7L);
        AgentRuntimeApproval approved = service.decideApproval(decision);
        assertEquals(AgentRuntimeApprovalStatusEnum.APPROVED, approved.getStatus());
        assertEquals(AgentRunStatusEnum.WAITING_APPROVAL, run.getStatus());

        AgentRuntimeApprovalAckRequest statusRequest = approvalAck(pending.getApproval().getId(), 1L);
        assertEquals(2L, service.getApprovalStatus(run.getId(), claim.getLeaseToken(), statusRequest)
                .getApproval().getRevision());
        AgentRuntimeApprovalAckRequest ack = approvalAck(pending.getApproval().getId(), 2L);
        assertEquals(AgentRuntimeApprovalStatusEnum.APPROVED,
                service.acknowledgeApproval(run.getId(), claim.getLeaseToken(), ack)
                        .getApproval().getStatus());
        assertEquals(AgentRunStatusEnum.RUNNING, run.getStatus());
    }

    @Test
    void uploadsValidatedArtifactBeforeCompletionAndKeepsExplicitTypeIdempotent() {
        AgentRuntimeDispatchServiceImpl service = serviceAt(NOW, "lease-token", "task-token");
        AgentRuntimeRunClaim claim = service.claim(instance.getId(), claimRequest());
        service.markStarted(run.getId(), claim.getLeaseToken(), startedRequest(1L));
        String json = "{\"charts\":[]}";
        AgentRuntimeArtifactManifest manifest = manifest("chart-1", AgentArtifactTypeEnum.CHART,
                "Channel chart", "application/json", json);
        AgentRuntimeArtifactUploadRequest upload = artifactUpload(manifest);

        AgentRuntimeArtifactResult created = service.uploadArtifact(run.getId(), claim.getLeaseToken(), upload);
        assertEquals(AgentArtifactTypeEnum.CHART, created.getArtifact().getType());
        assertEquals(AgentRunStatusEnum.RUNNING, run.getStatus());
        assertEquals(created.getArtifact().getId(), service.uploadArtifact(
                run.getId(), claim.getLeaseToken(), upload).getArtifact().getId());

        AgentRuntimeArtifactManifest conflict = manifest("chart-2", AgentArtifactTypeEnum.CHART,
                "Another chart", "application/json", "{\"charts\":[1]}");
        assertThrows(IllegalStateException.class, () -> service.uploadArtifact(
                run.getId(), claim.getLeaseToken(), artifactUpload(conflict)));

        AgentRuntimeArtifactManifest unsafeFile = manifest("file-1", AgentArtifactTypeEnum.FILE,
                "Export", "text/plain", "secret");
        unsafeFile.setContent(null);
        unsafeFile.setContentBase64(java.util.Base64.getEncoder().encodeToString(
                "secret".getBytes(StandardCharsets.UTF_8)));
        unsafeFile.setFileName("../secret.txt");
        assertThrows(IllegalArgumentException.class, () -> service.uploadArtifact(
                run.getId(), claim.getLeaseToken(), artifactUpload(unsafeFile)));

        AgentRuntimeRunCompleteRequest complete = terminalRequest(
                new AgentRuntimeRunCompleteRequest(), 1L, "terminal-with-artifact");
        complete.setExpectedLeaseRevision(runtimeStorage.lease.getRevision());
        complete.setFinalResponse("# Report");
        service.complete(run.getId(), claim.getLeaseToken(), complete);
        assertEquals(AgentRunStatusEnum.COMPLETED, run.getStatus());
        assertTrue(artifacts.containsKey(AgentArtifactTypeEnum.CHART));
        assertTrue(artifacts.containsKey(AgentArtifactTypeEnum.REPORT));
    }

    @Test
    void acceptsOnlySuccessfulEvidenceFromCurrentRunAndTaskDataScope() {
        evidenceAttempt = new AgentToolAttempt();
        evidenceAttempt.setId("attempt-1");
        evidenceAttempt.setRunId(run.getId());
        evidenceAttempt.setProposalId("proposal-1");
        evidenceAttempt.setStatus(AgentToolAttemptStatusEnum.SUCCEEDED);
        evidenceAttempt.setCompletedAt(Date.from(NOW));
        evidenceProposal = new AgentSqlProposal();
        evidenceProposal.setId("proposal-1");
        evidenceProposal.setDataSourceId(8L);
        evidenceProposal.setDatabaseName("sales");
        evidenceProposal.setSchemaName("public");
        evidenceProposal.setSqlSnapshot("select 1");
        evidenceProposal.setSqlHash("hash");
        AgentRuntimeArtifactEvidenceRef reference = new AgentRuntimeArtifactEvidenceRef();
        reference.setToolAttemptId(evidenceAttempt.getId());
        AgentRuntimeArtifactManifest manifest = manifest("report-evidence", AgentArtifactTypeEnum.REPORT,
                "Evidence report", "text/markdown", "# Evidence");
        manifest.setEvidence(List.of(reference));
        AgentRuntimeDispatchServiceImpl service = serviceAt(NOW, "lease-token", "task-token");
        AgentRuntimeRunClaim claim = service.claim(instance.getId(), claimRequest());
        service.markStarted(run.getId(), claim.getLeaseToken(), startedRequest(1L));

        assertThrows(SecurityException.class, () -> service.uploadArtifact(
                run.getId(), claim.getLeaseToken(), artifactUpload(manifest)));

        evidenceProposal.setDataSourceId(7L);
        service.uploadArtifact(run.getId(), claim.getLeaseToken(), artifactUpload(manifest));
        assertEquals("attempt-1", artifactRequest.get().getEvidence().get(0).getToolAttemptId());
        assertEquals(7L, artifactRequest.get().getEvidence().get(0).getDataSourceId());
    }

    private AgentRuntimeDispatchServiceImpl serviceAt(Instant instant, String... tokens) {
        Queue<String> suppliedTokens = new ArrayDeque<>(List.of(tokens));
        IAgentRuntimeControlService runtimeControl = proxy(IAgentRuntimeControlService.class,
                (proxy, method, args) -> {
                    if ("getInstance".equals(method.getName())) {
                        return instance;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
        IAgentRunService runService = proxy(IAgentRunService.class, (proxy, method, args) -> {
            return switch (method.getName()) {
                case "get" -> parentRun != null && parentRun.getId().equals(args[0]) ? parentRun : run;
                case "transition" -> transitionRun((AgentRunTransitionRequest) args[0]);
                default -> throw new UnsupportedOperationException(method.getName());
            };
        });
        IAgentTaskService taskService = proxy(IAgentTaskService.class, (proxy, method, args) -> {
            return switch (method.getName()) {
                case "get" -> task;
                case "listRuns" -> List.of(run);
                case "transition" -> transitionTask((AgentTaskTransitionRequest) args[0]);
                default -> throw new UnsupportedOperationException(method.getName());
            };
        });
        IAgentDefinitionService agentService = proxy(IAgentDefinitionService.class,
                (proxy, method, args) -> {
                    if ("get".equals(method.getName())) {
                        return agent;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
        IAgentControlStorage agentStorage = proxy(IAgentControlStorage.class,
                (proxy, method, args) -> {
                    return switch (method.getName()) {
                        case "listTaskContexts" -> List.of();
                        case "listRunEvents" -> runtimeStorage.event == null
                                ? List.of() : List.of(runtimeStorage.event);
                        case "appendRunEvent" -> args[0];
                        case "getToolAttempt" -> evidenceAttempt;
                        case "getSqlProposal" -> evidenceProposal;
                        default -> throw new UnsupportedOperationException(method.getName());
                    };
                });
        IAgentContextAssembler contextAssembler = (agent, task, runs) -> "assembled-context";
        IAgentArtifactService artifactService = proxy(IAgentArtifactService.class,
                (proxy, method, args) -> switch (method.getName()) {
                    case "create" -> {
                        AgentArtifactCreateRequest request = (AgentArtifactCreateRequest) args[0];
                        artifactRequest.set(request);
                        AgentArtifactDetail existing = artifacts.get(request.getType());
                        if (existing != null) yield existing;
                        if (request.getType() == AgentArtifactTypeEnum.REPORT) {
                            reportCreated.set(true);
                            List<?> blocks = (List<?>) request.getContent().get("blocks");
                            reportMarkdown.set(String.valueOf(((java.util.Map<?, ?>) blocks.get(0)).get("content")));
                        }
                        AgentArtifact artifact = new AgentArtifact();
                        artifact.setId("artifact-" + request.getType().name().toLowerCase());
                        artifact.setTaskId(task.getId());
                        artifact.setType(request.getType());
                        artifact.setTitle(request.getTitle());
                        artifact.setCreatedByRunId(request.getCreatedByRunId());
                        artifact.setCurrentVersion(1);
                        AgentArtifactVersion version = new AgentArtifactVersion();
                        version.setArtifactId(artifact.getId());
                        version.setVersion(1);
                        version.setContent(request.getContent());
                        AgentArtifactDetail detail = new AgentArtifactDetail();
                        detail.setArtifact(artifact);
                        detail.setVersions(List.of(version));
                        artifacts.put(request.getType(), detail);
                        yield detail;
                    }
                    case "listByTask" -> artifacts.values().stream()
                            .map(AgentArtifactDetail::getArtifact).toList();
                    case "get" -> artifacts.values().stream()
                            .filter(detail -> detail.getArtifact().getId().equals(args[0]))
                            .findFirst().orElse(null);
                    case "extractStructuredArtifacts" -> List.of();
                    case "satisfiesOutputContract" -> reportCreated.get();
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        return new AgentRuntimeDispatchServiceImpl(runtimeControl, runtimeStorage, agentStorage,
                runService, taskService, agentService, contextAssembler,
                artifactService,
                Clock.fixed(instant, ZoneOffset.UTC), suppliedTokens::remove,
                AgentRuntimeDispatchServiceImpl.DEFAULT_LEASE_MILLIS);
    }

    private AgentTask transitionTask(AgentTaskTransitionRequest request) {
        if (task.getRevision() != request.getExpectedRevision()) {
            throw new ConcurrentModificationException();
        }
        task.setStatus(request.getTargetStatus());
        task.setRevision(task.getRevision() + 1);
        return task;
    }

    private AgentRun transitionRun(AgentRunTransitionRequest request) {
        if (run.getRevision() != request.getExpectedRevision()) {
            throw new ConcurrentModificationException();
        }
        run.setStatus(request.getTargetStatus());
        run.setRevision(run.getRevision() + 1);
        return run;
    }

    private AgentRuntimeLeaseRenewRequest renewRequest(int attempt, long revision) {
        AgentRuntimeLeaseRenewRequest request = new AgentRuntimeLeaseRenewRequest();
        request.setDaemonId(instance.getDaemonId());
        request.setLeaseAttempt(attempt);
        request.setExpectedLeaseRevision(revision);
        return request;
    }

    private AgentRuntimeRunClaimRequest claimRequest() {
        AgentRuntimeRunClaimRequest request = new AgentRuntimeRunClaimRequest();
        request.setDaemonId(instance.getDaemonId());
        return request;
    }

    private AgentRuntimeRunStartedRequest startedRequest(long revision) {
        AgentRuntimeRunStartedRequest request = new AgentRuntimeRunStartedRequest();
        request.setDaemonId(instance.getDaemonId());
        request.setLeaseAttempt(1);
        request.setExpectedLeaseRevision(revision);
        request.setRuntimeExecutionId("codex-thread-1");
        return request;
    }

    private AgentRuntimeApprovalAckRequest approvalAck(String approvalId, long revision) {
        AgentRuntimeApprovalAckRequest request = new AgentRuntimeApprovalAckRequest();
        request.setDaemonId(instance.getDaemonId());
        request.setLeaseAttempt(1);
        request.setExpectedLeaseRevision(runtimeStorage.lease.getRevision());
        request.setApprovalId(approvalId);
        request.setExpectedApprovalRevision(revision);
        return request;
    }

    private AgentRuntimeArtifactUploadRequest artifactUpload(AgentRuntimeArtifactManifest manifest) {
        AgentRuntimeArtifactUploadRequest request = new AgentRuntimeArtifactUploadRequest();
        request.setDaemonId(instance.getDaemonId());
        request.setLeaseAttempt(1);
        request.setExpectedLeaseRevision(runtimeStorage.lease.getRevision());
        request.setManifest(manifest);
        return request;
    }

    private AgentRuntimeArtifactManifest manifest(String id, AgentArtifactTypeEnum type, String title,
                                                  String mimeType, String content) {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        AgentRuntimeArtifactManifest manifest = new AgentRuntimeArtifactManifest();
        manifest.setArtifactId(id);
        manifest.setType(type);
        manifest.setTitle(title);
        manifest.setMimeType(mimeType);
        manifest.setSize((long) bytes.length);
        manifest.setSha256(sha256(bytes));
        manifest.setContent(content);
        return manifest;
    }

    private String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private <T extends ai.chat2db.community.domain.api.model.request.agent.AgentRuntimeRunTerminalRequest>
    T terminalRequest(T request, long sequence, String eventId) {
        request.setDaemonId(instance.getDaemonId());
        request.setLeaseAttempt(1);
        request.setSequence(sequence);
        request.setEventId(eventId);
        request.setOccurredAt(Date.from(NOW));
        return request;
    }

    private AgentRuntimeEventRequest eventRequest(long sequence, String eventId) {
        AgentRuntimeEventRequest request = new AgentRuntimeEventRequest();
        request.setDaemonId(instance.getDaemonId());
        request.setLeaseAttempt(1);
        request.setSequence(sequence);
        request.setEventId(eventId);
        request.setEventType(AgentRuntimeEventTypeEnum.MESSAGE_DELTA);
        request.setContent("partial response");
        request.setOccurredAt(Date.from(NOW));
        return request;
    }

    @SuppressWarnings("unchecked")
    private <T> T proxy(Class<T> type, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{type}, handler);
    }

    private class MemoryRuntimeStorage implements IAgentRuntimeControlStorage {

        private final AgentRun run;
        private AgentRuntimeRunLease lease;
        private AgentRunEvent event;
        private final Map<String, AgentRuntimeApproval> approvals = new LinkedHashMap<>();

        private MemoryRuntimeStorage(AgentRun run) {
            this.run = run;
        }

        @Override
        public AgentRuntimeRunLease claimRuntimeRun(String instanceId, AgentRuntimeProviderEnum provider,
                                                    String leaseTokenHash, String taskTokenHash,
                                                    Date claimedAt, Date leaseExpiresAt) {
            lease = new AgentRuntimeRunLease();
            lease.setRunId(run.getId());
            lease.setRuntimeInstanceId(instanceId);
            lease.setLeaseAttempt(1);
            lease.setLeaseTokenHash(leaseTokenHash);
            lease.setTaskTokenHash(taskTokenHash);
            lease.setClaimedAt(claimedAt);
            lease.setLeaseExpiresAt(leaseExpiresAt);
            lease.setLastRenewedAt(claimedAt);
            lease.setLastEventSequence(0L);
            lease.setState(AgentRuntimeLeaseStateEnum.ACTIVE);
            lease.setRevision(1L);
            instance.setActiveRuns(instance.getActiveRuns() + 1);
            return lease;
        }

        @Override
        public AgentRuntimeRunLease getRuntimeRunLease(String runId) {
            return lease;
        }

        @Override
        public AgentRuntimeRunLease updateRuntimeRunLease(AgentRuntimeRunLease updated, long expectedRevision) {
            if (lease.getRevision() != expectedRevision) {
                throw new ConcurrentModificationException();
            }
            lease = updated;
            return lease;
        }

        @Override
        public AgentRuntimeRunLease startRuntimeRun(AgentRuntimeRunLease updated, long expectedLeaseRevision,
                                                    long expectedRunRevision) {
            if (lease.getRevision() != expectedLeaseRevision || run.getRevision() != expectedRunRevision) {
                throw new ConcurrentModificationException();
            }
            lease = updated;
            run.setStatus(AgentRunStatusEnum.RUNNING);
            run.setRevision(run.getRevision() + 1);
            return lease;
        }

        @Override
        public AgentRunEvent appendRuntimeRunEvent(AgentRunEvent requested, int leaseAttempt,
                                                   long runtimeSequence, Date acceptedAt,
                                                   String providerSessionId) {
            if (event != null && event.getEventId().equals(requested.getEventId())) {
                return event;
            }
            if (lease.getLeaseAttempt() != leaseAttempt
                    || runtimeSequence != lease.getLastEventSequence() + 1) {
                throw new IllegalStateException("out of order runtime event");
            }
            requested.setSequence(1L);
            event = requested;
            lease.setLastEventSequence(runtimeSequence);
            lease.setRevision(lease.getRevision() + 1);
            if (providerSessionId != null) {
                run.setProviderSessionId(providerSessionId);
                run.setRevision(run.getRevision() + 1);
            }
            return event;
        }

        @Override
        public AgentRuntimeRunLease requestRuntimeRunCancellation(String runId, Date requestedAt) {
            lease.setCancelRequestedAt(requestedAt);
            lease.setRevision(lease.getRevision() + 1);
            return lease;
        }

        @Override public AgentRuntimeApproval createOrGetRuntimeApproval(AgentRuntimeApproval approval) {
            AgentRuntimeApproval existing = findRuntimeApproval(approval.getRunId(), approval.getLeaseAttempt(),
                    approval.getProviderRequestId());
            if (existing != null) return existing;
            approvals.put(approval.getId(), approval);
            return approval;
        }
        @Override public AgentRuntimeApproval getRuntimeApproval(String id) { return approvals.get(id); }
        @Override public AgentRuntimeApproval findRuntimeApproval(String runId, int attempt, String requestId) {
            return approvals.values().stream().filter(value -> value.getRunId().equals(runId)
                    && value.getLeaseAttempt() == attempt
                    && value.getProviderRequestId().equals(requestId)).findFirst().orElse(null);
        }
        @Override public List<AgentRuntimeApproval> listRuntimeApprovals(String runId) {
            return approvals.values().stream().filter(value -> value.getRunId().equals(runId)).toList();
        }
        @Override public AgentRuntimeApproval updateRuntimeApproval(AgentRuntimeApproval approval, long revision) {
            if (approvals.get(approval.getId()).getRevision() != revision) throw new ConcurrentModificationException();
            approvals.put(approval.getId(), approval);
            return approval;
        }

        @Override
        public AgentRuntimeRunLease finishRuntimeRun(AgentRuntimeRunLease updated, AgentRunEvent terminalEvent,
                                                     AgentRunStatusEnum targetStatus, String failureReason,
                                                     String resultSummary, Date completedAt,
                                                     long expectedLeaseRevision, long expectedRunRevision) {
            if (lease.getState() != AgentRuntimeLeaseStateEnum.ACTIVE) {
                return lease;
            }
            if (lease.getRevision() != expectedLeaseRevision || run.getRevision() != expectedRunRevision
                    || terminalEvent.getRuntimeSequence() != lease.getLastEventSequence() + 1) {
                throw new ConcurrentModificationException();
            }
            lease.setState(switch (targetStatus) {
                case COMPLETED -> AgentRuntimeLeaseStateEnum.COMPLETED;
                case FAILED -> AgentRuntimeLeaseStateEnum.FAILED;
                case CANCELLED -> AgentRuntimeLeaseStateEnum.CANCELLED;
                case UNKNOWN -> AgentRuntimeLeaseStateEnum.UNKNOWN;
                default -> throw new IllegalArgumentException();
            });
            lease.setReleasedAt(completedAt);
            lease.setTerminalEventId(terminalEvent.getEventId());
            lease.setLastEventSequence(terminalEvent.getRuntimeSequence());
            lease.setRevision(lease.getRevision() + 1);
            run.setStatus(targetStatus);
            run.setFailureReason(failureReason);
            run.setResultSummary(resultSummary);
            run.setRevision(run.getRevision() + 1);
            instance.setActiveRuns(instance.getActiveRuns() - 1);
            event = terminalEvent;
            return lease;
        }

        @Override
        public List<String> reconcileExpiredRuntimeRuns(Date expiredAt, int limit) {
            return List.of();
        }

        @Override
        public AgentRuntimeProfile createRuntimeProfile(AgentRuntimeProfile profile) {
            throw new UnsupportedOperationException();
        }

        @Override
        public AgentRuntimeProfile updateRuntimeProfile(AgentRuntimeProfile profile, long expectedRevision) {
            throw new UnsupportedOperationException();
        }

        @Override
        public AgentRuntimeProfile getRuntimeProfile(String id) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<AgentRuntimeProfile> listRuntimeProfiles() {
            throw new UnsupportedOperationException();
        }

        @Override
        public AgentRuntimeInstance createRuntimeInstance(AgentRuntimeInstance instance) {
            throw new UnsupportedOperationException();
        }

        @Override
        public AgentRuntimeInstance updateRuntimeInstance(AgentRuntimeInstance instance, long expectedRevision) {
            throw new UnsupportedOperationException();
        }

        @Override
        public AgentRuntimeInstance heartbeatRuntimeInstance(String instanceId, String daemonId,
                                                              AgentRuntimeInstanceStatusEnum status,
                                                              Date heartbeatAt) {
            throw new UnsupportedOperationException();
        }

        @Override
        public AgentRuntimeInstance getRuntimeInstance(String id) {
            throw new UnsupportedOperationException();
        }

        @Override
        public AgentRuntimeInstance findRuntimeInstance(String daemonId, AgentRuntimeProviderEnum provider) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<AgentRuntimeInstance> listRuntimeInstances() {
            throw new UnsupportedOperationException();
        }
    }
}

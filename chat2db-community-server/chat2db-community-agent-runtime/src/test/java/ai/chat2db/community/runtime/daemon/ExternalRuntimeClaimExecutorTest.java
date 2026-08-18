package ai.chat2db.community.runtime.daemon;

import ai.chat2db.community.domain.api.enums.agent.AgentRuntimeEventTypeEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentRuntimeApprovalStatusEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentRuntimeProviderEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentRunStatusEnum;
import ai.chat2db.community.domain.api.model.agent.AgentRuntimeApproval;
import ai.chat2db.community.domain.api.model.agent.AgentRuntimeApprovalResult;
import ai.chat2db.community.domain.api.model.agent.AgentRuntimeArtifactManifest;
import ai.chat2db.community.domain.api.model.agent.AgentRuntimeArtifactResult;
import ai.chat2db.community.domain.api.model.agent.AgentArtifact;
import ai.chat2db.community.domain.api.model.agent.AgentRuntimeEventAccepted;
import ai.chat2db.community.domain.api.model.agent.AgentRuntimeLeaseStatus;
import ai.chat2db.community.domain.api.model.agent.AgentRuntimeMcpEndpoint;
import ai.chat2db.community.domain.api.model.agent.AgentRuntimeProfile;
import ai.chat2db.community.domain.api.model.agent.AgentRuntimeRunClaim;
import ai.chat2db.community.domain.api.model.agent.AgentRuntimeRunTerminalResult;
import ai.chat2db.community.domain.api.model.agent.runtime.AgentRuntimeEvent;
import ai.chat2db.community.domain.api.model.agent.runtime.AgentRuntimeStartRequest;
import ai.chat2db.community.domain.api.model.request.agent.AgentRuntimeEventRequest;
import ai.chat2db.community.domain.api.model.request.agent.AgentRuntimeApprovalAckRequest;
import ai.chat2db.community.domain.api.model.request.agent.AgentRuntimeApprovalRequest;
import ai.chat2db.community.domain.api.model.request.agent.AgentRuntimeArtifactUploadRequest;
import ai.chat2db.community.domain.api.model.request.agent.AgentRuntimeLeaseRenewRequest;
import ai.chat2db.community.domain.api.model.request.agent.AgentRuntimeRunCancelAckRequest;
import ai.chat2db.community.domain.api.model.request.agent.AgentRuntimeRunCompleteRequest;
import ai.chat2db.community.domain.api.model.request.agent.AgentRuntimeRunFailRequest;
import ai.chat2db.community.domain.api.model.request.agent.AgentRuntimeRunStartedRequest;
import ai.chat2db.community.domain.api.model.request.agent.AgentRuntimeRunSuspendRequest;
import ai.chat2db.community.runtime.provider.ExternalProviderAdapter;
import ai.chat2db.community.runtime.provider.ProviderEventSink;
import ai.chat2db.community.runtime.provider.ProviderExecutionException;
import ai.chat2db.community.runtime.provider.ProviderExecutionRequest;
import ai.chat2db.community.runtime.provider.ProviderExecutionResult;
import ai.chat2db.community.runtime.provider.ProviderFailureKind;
import ai.chat2db.community.runtime.provider.ProviderLifecycleSink;
import ai.chat2db.community.runtime.provider.ProviderApprovalDecision;
import ai.chat2db.community.runtime.provider.ProviderApprovalRequest;
import ai.chat2db.community.runtime.workspace.TaskWorkspaceManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExternalRuntimeClaimExecutorTest {

    private static final Instant NOW = Instant.parse("2026-08-15T08:00:00Z");

    @TempDir
    Path temporaryDirectory;

    @Test
    void forwardsStartedEventsAndCompletionWithCurrentLeaseRevisionThenCleansWorkspace() {
        FakeControlPlane control = new FakeControlPlane();
        ExternalProviderAdapter adapter = new ExternalProviderAdapter() {
            @Override public AgentRuntimeProviderEnum provider() { return AgentRuntimeProviderEnum.CODEX; }

            @Override
            public ProviderExecutionResult execute(ProviderExecutionRequest request, ProviderEventSink events,
                                                   ProviderLifecycleSink lifecycle) {
                assertTrue(Files.isDirectory(request.getWorkingDirectory()));
                assertEquals("/safe/bin", request.getEnvironment().get("PATH"));
                assertEquals("task-secret", request.getEnvironment().get(ExternalRuntimeClaimExecutor.TASK_TOKEN_ENV));
                assertEquals(2, request.getEnvironment().size());
                assertEquals(URI.create("http://127.0.0.1:10825/api/agent/runtime/mcp/runs/run-1"),
                        request.getMcpEndpoints().get(0).getUrl());
                assertEquals("thread-existing", request.getResumeSessionId());
                lifecycle.started("thread-1");
                AgentRuntimeEvent event = new AgentRuntimeEvent();
                event.setEventId("message-1");
                event.setRunId(request.getRunId());
                event.setType(AgentRuntimeEventTypeEnum.MESSAGE_DELTA);
                event.setContent("answer task-secret");
                event.setPayload(Map.of(
                        "authorization", "Bearer task-secret",
                        "nested", java.util.List.of(Map.of("command", "TOKEN=task-secret"))));
                event.setOccurredAt(Date.from(NOW));
                events.emit(event);
                ProviderExecutionResult result = new ProviderExecutionResult();
                result.setSessionId("thread-1");
                result.setTurnId("turn-1");
                result.setFinalResponse("# Report\nTOKEN=task-secret");
                result.setArtifacts(java.util.List.of(manifest("runtime-chart-1", "{\"charts\":[]}")));
                return result;
            }

            @Override public void cancel(String runId) { }
        };
        TaskWorkspaceManager workspaces = new TaskWorkspaceManager(
                temporaryDirectory.resolve("runtime").toAbsolutePath());

        executor(control, adapter, workspaces).execute(claim(60_000L), Map.of("PATH", "/safe/bin"));

        assertEquals("thread-1", control.started.getRuntimeExecutionId());
        assertEquals(1L, control.event.getSequence());
        assertEquals(2L, control.event.getExpectedLeaseRevision());
        assertEquals(2L, control.complete.getSequence());
        assertEquals(4L, control.complete.getExpectedLeaseRevision());
        assertEquals("answer [REDACTED]", control.event.getContent());
        assertEquals("Bearer [REDACTED]", control.event.getPayload().get("authorization"));
        assertFalse(control.event.getPayload().toString().contains("task-secret"));
        assertEquals("# Report\nTOKEN=[REDACTED]", control.complete.getFinalResponse());
        assertEquals("runtime-chart-1", control.artifactUpload.getManifest().getArtifactId());
        assertFalse(Files.exists(temporaryDirectory.resolve("runtime/run-run-1-attempt-1")));
    }

    @Test
    void renewalCancellationInterruptsProviderAndAcknowledgesCancellation() throws Exception {
        FakeControlPlane control = new FakeControlPlane();
        control.cancelOnRenew.set(true);
        CountDownLatch executing = new CountDownLatch(1);
        ExternalProviderAdapter adapter = new ExternalProviderAdapter() {
            private final AtomicBoolean cancelled = new AtomicBoolean();
            @Override public AgentRuntimeProviderEnum provider() { return AgentRuntimeProviderEnum.CODEX; }
            @Override
            public ProviderExecutionResult execute(ProviderExecutionRequest request, ProviderEventSink events,
                                                   ProviderLifecycleSink lifecycle) {
                lifecycle.started("thread-cancel");
                executing.countDown();
                while (!cancelled.get()) {
                    try { Thread.sleep(20L); } catch (InterruptedException ignored) { }
                }
                throw new ProviderExecutionException(ProviderFailureKind.CANCELLED, "cancelled");
            }
            @Override public void cancel(String runId) { cancelled.set(true); }
        };
        Thread runner = new Thread(() -> executor(control, adapter, new TaskWorkspaceManager(
                temporaryDirectory.resolve("cancel-runtime").toAbsolutePath()))
                .execute(claim(3_000L), Map.of()));
        runner.start();
        assertTrue(executing.await(5, TimeUnit.SECONDS));
        runner.join(5_000L);

        assertFalse(runner.isAlive());
        assertNotNull(control.cancelAck);
        assertEquals(4L, control.cancelAck.getExpectedLeaseRevision());
        assertEquals(1L, control.cancelAck.getSequence());
    }

    @Test
    void reportsUnexpectedProviderExitAsUnknownOutcome() {
        FakeControlPlane control = new FakeControlPlane();
        ExternalProviderAdapter adapter = new ExternalProviderAdapter() {
            @Override public AgentRuntimeProviderEnum provider() { return AgentRuntimeProviderEnum.CODEX; }
            @Override
            public ProviderExecutionResult execute(ProviderExecutionRequest request, ProviderEventSink events,
                                                   ProviderLifecycleSink lifecycle) {
                lifecycle.started("thread-exit");
                throw new ProviderExecutionException(ProviderFailureKind.PROCESS_EXIT, "exit 17");
            }
            @Override public void cancel(String runId) { }
        };

        executor(control, adapter, new TaskWorkspaceManager(
                temporaryDirectory.resolve("exit-runtime").toAbsolutePath()))
                .execute(claim(60_000L), Map.of());

        assertEquals("exit 17", control.fail.getFailureReason());
        assertTrue(control.fail.getOutcomeUnknown());
        assertEquals(3L, control.fail.getExpectedLeaseRevision());
    }

    @Test
    void acceptsHermesClaimsThroughTheSharedLeaseExecutor() {
        FakeControlPlane control = new FakeControlPlane();
        AgentRuntimeRunClaim claim = claim(60_000L);
        claim.getRuntimeProfile().setProvider(AgentRuntimeProviderEnum.HERMES);
        ExternalProviderAdapter adapter = new ExternalProviderAdapter() {
            @Override public AgentRuntimeProviderEnum provider() { return AgentRuntimeProviderEnum.HERMES; }
            @Override
            public ProviderExecutionResult execute(ProviderExecutionRequest request, ProviderEventSink events,
                                                   ProviderLifecycleSink lifecycle) {
                lifecycle.started("hermes-process-1");
                ProviderExecutionResult result = new ProviderExecutionResult();
                result.setFinalResponse("Hermes report");
                return result;
            }
            @Override public void cancel(String runId) { }
        };

        executor(control, adapter, new TaskWorkspaceManager(
                temporaryDirectory.resolve("hermes-runtime").toAbsolutePath()))
                .execute(claim, Map.of());

        assertEquals("hermes-process-1", control.started.getRuntimeExecutionId());
        assertEquals("Hermes report", control.complete.getFinalResponse());
    }

    @Test
    void rejectsInvalidOptionalArtifactButStillCompletesWithFinalResponse() {
        FakeControlPlane control = new FakeControlPlane();
        control.rejectArtifact.set(true);
        ExternalProviderAdapter adapter = new ExternalProviderAdapter() {
            @Override public AgentRuntimeProviderEnum provider() { return AgentRuntimeProviderEnum.CODEX; }

            @Override
            public ProviderExecutionResult execute(ProviderExecutionRequest request, ProviderEventSink events,
                                                   ProviderLifecycleSink lifecycle) {
                lifecycle.started("codex-with-invalid-artifact");
                ProviderExecutionResult result = new ProviderExecutionResult();
                result.setFinalResponse("Report survives an invalid optional artifact");
                result.setArtifacts(java.util.List.of(manifest("invalid-evidence", "{\"charts\":[]}")));
                return result;
            }

            @Override public void cancel(String runId) { }
        };

        executor(control, adapter, new TaskWorkspaceManager(
                temporaryDirectory.resolve("invalid-artifact-runtime").toAbsolutePath()))
                .execute(claim(60_000L), Map.of());

        assertNotNull(control.complete);
        assertEquals("Report survives an invalid optional artifact", control.complete.getFinalResponse());
        assertNull(control.fail);
        assertEquals(AgentRuntimeEventTypeEnum.ERROR, control.event.getEventType());
        assertEquals(Boolean.TRUE, control.event.getPayload().get("recoverable"));
        assertEquals(4L, control.complete.getExpectedLeaseRevision());
    }

    @Test
    void bridgesProviderPermissionThroughControlPlaneBeforeResumingExecution() {
        FakeControlPlane control = new FakeControlPlane();
        AgentRuntimeRunClaim claim = claim(60_000L);
        claim.getRuntimeProfile().setProvider(AgentRuntimeProviderEnum.HERMES);
        claim.getRuntimeProfile().setApprovalBridgeEnabled(true);
        ExternalProviderAdapter adapter = new ExternalProviderAdapter() {
            @Override public AgentRuntimeProviderEnum provider() { return AgentRuntimeProviderEnum.HERMES; }

            @Override
            public ProviderExecutionResult execute(ProviderExecutionRequest request, ProviderEventSink events,
                                                   ProviderLifecycleSink lifecycle) {
                lifecycle.started("hermes-process-approval");
                ProviderApprovalRequest approval = new ProviderApprovalRequest();
                approval.setProviderRequestId("rpc-91");
                approval.setToolCallId("tool-1");
                approval.setTitle("Allow queryData?");
                approval.setPayload(Map.of("tool", "queryData"));
                approval.setAllowOptionId("allow-once");
                approval.setRejectOptionId("reject-once");
                ProviderApprovalDecision decision = request.getApprovalHandler().request(approval);
                assertTrue(decision.isApproved());
                assertEquals("allow-once", decision.getSelectedOptionId());
                ProviderExecutionResult result = new ProviderExecutionResult();
                result.setFinalResponse("approved result");
                return result;
            }

            @Override public void cancel(String runId) { }
        };

        executor(control, adapter, new TaskWorkspaceManager(
                temporaryDirectory.resolve("approval-runtime").toAbsolutePath()))
                .execute(claim, Map.of());

        assertEquals("rpc-91", control.approvalRequest.getProviderRequestId());
        assertEquals("runtime-approval-1", control.approvalAck.getApprovalId());
        assertEquals(2L, control.approvalAck.getExpectedApprovalRevision());
        assertEquals("approved result", control.complete.getFinalResponse());
        assertEquals(6L, control.complete.getExpectedLeaseRevision());
    }

    @Test
    void suspendsLeaseInsteadOfCompletingWhenSqlApprovalContinuationExists() {
        FakeControlPlane control = new FakeControlPlane();
        control.sqlContinuationOnRenew.set(true);
        ExternalProviderAdapter adapter = new ExternalProviderAdapter() {
            @Override public AgentRuntimeProviderEnum provider() { return AgentRuntimeProviderEnum.CODEX; }

            @Override
            public ProviderExecutionResult execute(ProviderExecutionRequest request, ProviderEventSink events,
                                                   ProviderLifecycleSink lifecycle) {
                lifecycle.started("thread-awaiting-sql");
                ProviderExecutionResult result = new ProviderExecutionResult();
                result.setFinalResponse("SQL approval required");
                return result;
            }

            @Override public void cancel(String runId) { }
        };

        executor(control, adapter, new TaskWorkspaceManager(
                temporaryDirectory.resolve("sql-approval-runtime").toAbsolutePath()))
                .execute(claim(60_000L), Map.of());

        assertNotNull(control.suspend);
        assertEquals(3L, control.suspend.getExpectedLeaseRevision());
        assertNull(control.complete);
        assertNull(control.fail);
    }

    @Test
    void toleratesTransientControlPlaneRestartWhileAcknowledgedLeaseIsValid() throws Exception {
        FakeControlPlane control = new FakeControlPlane();
        control.renewFailures.set(1);
        AtomicBoolean cancelled = new AtomicBoolean();
        ExternalProviderAdapter adapter = new ExternalProviderAdapter() {
            @Override public AgentRuntimeProviderEnum provider() { return AgentRuntimeProviderEnum.CODEX; }

            @Override
            public ProviderExecutionResult execute(ProviderExecutionRequest request, ProviderEventSink events,
                                                   ProviderLifecycleSink lifecycle) {
                lifecycle.started("codex-process-restart");
                try {
                    Thread.sleep(2_500L);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
                ProviderExecutionResult result = new ProviderExecutionResult();
                result.setFinalResponse("recovered");
                return result;
            }

            @Override public void cancel(String runId) { cancelled.set(true); }
        };

        executor(control, adapter, new TaskWorkspaceManager(
                temporaryDirectory.resolve("restart-runtime").toAbsolutePath()))
                .execute(claim(3_000L), Map.of());

        assertFalse(cancelled.get());
        assertNotNull(control.complete);
        assertTrue(control.renewCalls.get() >= 2);
    }

    private ExternalRuntimeClaimExecutor executor(FakeControlPlane control, ExternalProviderAdapter adapter,
                                        TaskWorkspaceManager workspaces) {
        return new ExternalRuntimeClaimExecutor("daemon-1", control, adapter, workspaces,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private AgentRuntimeRunClaim claim(long leaseMillis) {
        AgentRuntimeProfile profile = new AgentRuntimeProfile();
        profile.setProvider(AgentRuntimeProviderEnum.CODEX);
        profile.setExecutable("/fake/codex");
        AgentRuntimeRunClaim claim = new AgentRuntimeRunClaim();
        claim.setRunId("run-1");
        claim.setTaskId("task-1");
        claim.setLeaseAttempt(1);
        claim.setLeaseRevision(1L);
        claim.setLeaseToken("lease-secret");
        claim.setTaskScopedToken("task-secret");
        claim.setResumeSessionId("thread-existing");
        claim.setLeaseExpiresAt(new Date(NOW.toEpochMilli() + leaseMillis));
        AgentRuntimeMcpEndpoint endpoint = new AgentRuntimeMcpEndpoint();
        endpoint.setName("chat2db_task_tools");
        endpoint.setTransport("STREAMABLE_HTTP");
        endpoint.setPath("/api/agent/runtime/mcp/runs/run-1");
        endpoint.setBearerTokenEnvironmentVariable(ExternalRuntimeClaimExecutor.TASK_TOKEN_ENV);
        claim.setMcpEndpoints(java.util.List.of(endpoint));
        claim.setRuntimeProfile(profile);
        claim.setStartRequest(new AgentRuntimeStartRequest());
        return claim;
    }

    private AgentRuntimeArtifactManifest manifest(String id, String content) {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        AgentRuntimeArtifactManifest manifest = new AgentRuntimeArtifactManifest();
        manifest.setArtifactId(id);
        manifest.setType(ai.chat2db.community.domain.api.enums.agent.AgentArtifactTypeEnum.CHART);
        manifest.setTitle("Runtime chart");
        manifest.setMimeType("application/json");
        manifest.setSize((long) bytes.length);
        try {
            manifest.setSha256(HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
        manifest.setContent(content);
        return manifest;
    }

    private static final class FakeControlPlane extends AgentRuntimeControlPlaneClient {
        private long revision = 1L;
        private final AtomicBoolean cancelOnRenew = new AtomicBoolean();
        private final AtomicInteger renewFailures = new AtomicInteger();
        private final AtomicInteger renewCalls = new AtomicInteger();
        private final AtomicBoolean rejectArtifact = new AtomicBoolean();
        private final AtomicBoolean sqlContinuationOnRenew = new AtomicBoolean();
        private AgentRuntimeRunStartedRequest started;
        private AgentRuntimeEventRequest event;
        private AgentRuntimeRunCompleteRequest complete;
        private AgentRuntimeRunFailRequest fail;
        private AgentRuntimeRunCancelAckRequest cancelAck;
        private AgentRuntimeApprovalRequest approvalRequest;
        private AgentRuntimeApprovalAckRequest approvalAck;
        private AgentRuntimeArtifactUploadRequest artifactUpload;
        private AgentRuntimeRunSuspendRequest suspend;

        private FakeControlPlane() {
            super(URI.create("http://127.0.0.1:10825/"), "daemon-secret");
        }

        @Override
        public AgentRuntimeLeaseStatus started(String runId, String leaseToken,
                                               AgentRuntimeRunStartedRequest request) {
            started = request;
            return status(++revision, false);
        }

        @Override
        public AgentRuntimeLeaseStatus renew(String runId, String leaseToken,
                                             AgentRuntimeLeaseRenewRequest request) {
            renewCalls.incrementAndGet();
            if (renewFailures.getAndUpdate(value -> Math.max(0, value - 1)) > 0) {
                throw new ControlPlaneException("control plane restarting");
            }
            AgentRuntimeLeaseStatus status = status(++revision, cancelOnRenew.get());
            if (sqlContinuationOnRenew.get()) {
                status.setRunStatus(AgentRunStatusEnum.WAITING_APPROVAL);
                status.setApprovalDecisionPending(true);
                status.setSqlContinuationAvailable(true);
            }
            return status;
        }

        @Override
        public AgentRuntimeEventAccepted event(String runId, String leaseToken,
                                               AgentRuntimeEventRequest request) {
            event = request;
            AgentRuntimeEventAccepted accepted = new AgentRuntimeEventAccepted();
            accepted.setLease(status(++revision, false));
            return accepted;
        }

        @Override
        public AgentRuntimeApprovalResult requestApproval(String runId, String leaseToken,
                                                          AgentRuntimeApprovalRequest request) {
            approvalRequest = request;
            return approvalResult(AgentRuntimeApprovalStatusEnum.PENDING, 1L, ++revision);
        }

        @Override
        public AgentRuntimeApprovalResult approvalStatus(String runId, String leaseToken,
                                                         AgentRuntimeApprovalAckRequest request) {
            return approvalResult(AgentRuntimeApprovalStatusEnum.APPROVED, 2L, ++revision);
        }

        @Override
        public AgentRuntimeApprovalResult acknowledgeApproval(String runId, String leaseToken,
                                                              AgentRuntimeApprovalAckRequest request) {
            approvalAck = request;
            return approvalResult(AgentRuntimeApprovalStatusEnum.APPROVED, 2L, ++revision);
        }

        @Override
        public AgentRuntimeArtifactResult uploadArtifact(String runId, String leaseToken,
                                                         AgentRuntimeArtifactUploadRequest request) {
            artifactUpload = request;
            if (rejectArtifact.get()) {
                throw new ControlPlaneRejectedException("runtime.artifact.invalid", "invalid evidence");
            }
            AgentArtifact artifact = new AgentArtifact();
            artifact.setId("server-artifact-1");
            artifact.setType(request.getManifest().getType());
            AgentRuntimeArtifactResult result = new AgentRuntimeArtifactResult();
            result.setArtifact(artifact);
            result.setLease(status(revision, false));
            return result;
        }

        @Override
        public AgentRuntimeRunTerminalResult complete(String runId, String leaseToken,
                                                       AgentRuntimeRunCompleteRequest request) {
            complete = request;
            return new AgentRuntimeRunTerminalResult();
        }

        @Override
        public AgentRuntimeRunTerminalResult fail(String runId, String leaseToken,
                                                   AgentRuntimeRunFailRequest request) {
            fail = request;
            return new AgentRuntimeRunTerminalResult();
        }

        @Override
        public AgentRuntimeRunTerminalResult cancelAck(String runId, String leaseToken,
                                                        AgentRuntimeRunCancelAckRequest request) {
            cancelAck = request;
            return new AgentRuntimeRunTerminalResult();
        }

        @Override
        public AgentRuntimeLeaseStatus suspendForSqlApproval(String runId, String leaseToken,
                                                              AgentRuntimeRunSuspendRequest request) {
            suspend = request;
            AgentRuntimeLeaseStatus status = status(++revision, false);
            status.setRunStatus(AgentRunStatusEnum.WAITING_APPROVAL);
            status.setSqlContinuationAvailable(true);
            status.setState(ai.chat2db.community.domain.api.enums.agent.AgentRuntimeLeaseStateEnum.SUSPENDED);
            return status;
        }

        private AgentRuntimeLeaseStatus status(long value, boolean cancelRequested) {
            AgentRuntimeLeaseStatus status = new AgentRuntimeLeaseStatus();
            status.setLeaseRevision(value);
            status.setCancelRequested(cancelRequested);
            status.setLeaseExpiresAt(new Date(NOW.toEpochMilli() + 60_000L));
            return status;
        }

        private AgentRuntimeApprovalResult approvalResult(AgentRuntimeApprovalStatusEnum approvalStatus,
                                                          long approvalRevision, long leaseRevision) {
            AgentRuntimeApproval approval = new AgentRuntimeApproval();
            approval.setId("runtime-approval-1");
            approval.setRunId("run-1");
            approval.setLeaseAttempt(1);
            approval.setProviderRequestId("rpc-91");
            approval.setAllowOptionId("allow-once");
            approval.setRejectOptionId("reject-once");
            approval.setStatus(approvalStatus);
            approval.setRevision(approvalRevision);
            AgentRuntimeApprovalResult result = new AgentRuntimeApprovalResult();
            result.setApproval(approval);
            result.setLease(status(leaseRevision, false));
            return result;
        }
    }
}
